package soy.iko.opencode.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.SessionDeleted
import soy.iko.opencode.data.model.SessionUpdated
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SessionListState(
    val sessions: List<Session> = emptyList(),
    val query: String = "",
    val previews: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** Sessions filtered by the current search query (title or preview text). */
    val filtered: List<Session>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return sessions
            return sessions.filter { session ->
                session.displayTitle.contains(q, ignoreCase = true) ||
                    (previews[session.id]?.contains(q, ignoreCase = true) == true)
            }
        }
}

class SessionListViewModel(private val container: AppContainer) : ViewModel() {

    /** Live SSE connection state, surfaced so the list can show a reconnect banner. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionState: StateFlow<EventStreamClient.ConnectionState> =
        container.activeConnection
            .flatMapLatest { it?.events?.state ?: flowOf(EventStreamClient.ConnectionState.Disconnected) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = EventStreamClient.ConnectionState.Disconnected,
            )

    /** Sessions with activity that arrived while not being viewed. */
    val unread: StateFlow<Set<String>> = container.unread

    val profiles: StateFlow<List<ServerProfile>> =
        container.profileStore.profiles.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _serverLabel = MutableStateFlow(
        container.activeConnection.value?.profile?.displayLabel ?: "opencode"
    )
    val serverLabel: StateFlow<String> = _serverLabel.asStateFlow()

    private val _switchingId = MutableStateFlow<String?>(null)
    val switchingId: StateFlow<String?> = _switchingId.asStateFlow()

    private val _state = MutableStateFlow(SessionListState())
    val state: StateFlow<SessionListState> = _state.asStateFlow()

    /** Tracks an in-flight manual refresh so pull-to-refresh can show its indicator. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Transient errors (failed create/delete/rename) surfaced as a snackbar, not by hiding the list. */
    private val _transientError = MutableStateFlow<String?>(null)
    val transientError: StateFlow<String?> = _transientError.asStateFlow()
    fun clearTransientError() { _transientError.value = null }

    private var previewJob: Job? = null

    /** Per-session in-flight preview loads, so rapid SSE events for the same session
     *  coalesce into a single request instead of firing one per event. Accessed from
     *  multiple coroutines (the SSE event handler and [loadPreview]), so kept concurrent. */
    private val livePreviewJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val activeProfileId: String?
        get() = container.activeConnection.value?.profile?.id

    init { refresh(); observeSessionEvents() }

    /**
     * Keep the session list live by reacting to SSE session events. New/renamed
     * sessions upsert and re-sort; deleted sessions drop immediately — no manual
     * refresh needed when activity happens server-side.
     */
    private fun observeSessionEvents() {
        viewModelScope.launch {
            container.activeConnection.collectLatest { conn ->
                if (conn == null) return@collectLatest
                conn.events.events.collect { event ->
                    when (event) {
                        is SessionUpdated -> {
                            val session = event.properties.info
                            _state.update { s ->
                                val updated = (s.sessions.filterNot { it.id == session.id } + session)
                                    .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0 }
                                s.copy(sessions = updated)
                            }
                            loadPreview(session.id)
                        }
                        is SessionDeleted -> {
                            val id = event.properties.info?.id ?: event.properties.sessionID
                            if (id != null) {
                                livePreviewJobs.remove(id)?.cancel()
                                container.draftStore.remove(id)
                                _state.update { s ->
                                    s.copy(
                                        sessions = s.sessions.filterNot { it.id == id },
                                        previews = s.previews - id,
                                    )
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun refresh() {
        val conn = container.activeConnection.value
        if (conn == null) {
            if (_state.value.sessions.isEmpty()) {
                _state.value = SessionListState(loading = false, error = container.string(R.string.not_connected))
            } else {
                _state.update { it.copy(loading = false) }
                _transientError.value = container.string(R.string.not_connected)
            }
            return
        }
        _serverLabel.value = conn.profile.displayLabel
        _state.update { it.copy(loading = true, error = null) }
        _refreshing.value = true
        viewModelScope.launch {
            runCatching { conn.repository.listSessions() }
                .onSuccess { list ->
                    val sorted = list.sortedByDescending { it.time?.updated ?: it.time?.created ?: 0 }
                    _state.update { it.copy(sessions = sorted, loading = false, error = null) }
                    loadPreviews(sorted)
                }
                .onFailure {
                    if (_state.value.sessions.isNotEmpty()) {
                        _state.update { it.copy(loading = false) }
                        _transientError.value = container.friendlyError(it)
                    } else {
                        _state.value = SessionListState(loading = false, error = container.friendlyError(it))
                    }
                }
            _refreshing.value = false
        }
    }

    /**
     * Reload the preview for a single session (used by the live SSE handler).
     * Cancels any prior in-flight load for the same session so a burst of
     * [SessionUpdated] events coalesces into a single request.
     */
    private fun loadPreview(sessionId: String) {
        val conn = container.activeConnection.value ?: return
        val api = conn.api
        livePreviewJobs.remove(sessionId)?.cancel()
        livePreviewJobs[sessionId] = viewModelScope.launch {
            try {
                val preview = runCatching {
                    api.listMessages(sessionId).lastOrNull()?.let { msg ->
                        msg.parts.filterIsInstance<TextPart>().lastOrNull()?.text
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() }?.take(NetworkConfig.previewTextMaxLength) ?: return@launch
                _state.update { s -> s.copy(previews = s.previews + (sessionId to preview)) }
            } finally {
                livePreviewJobs.remove(sessionId)
            }
        }
    }

    /** Fetch the last text part of each session for list previews (best-effort, bounded). */
    private fun loadPreviews(sessions: List<Session>) {
        previewJob?.cancel()
        val conn = container.activeConnection.value ?: return
        val api = conn.api
        _state.update { it.copy(previews = emptyMap()) }
        previewJob = viewModelScope.launch {
            val semaphore = Semaphore(NetworkConfig.maxConcurrentPreviews)
            sessions.take(NetworkConfig.maxPreviewSessions).map { session ->
                launch {
                    semaphore.withPermit {
                        val preview = runCatching {
                            api.listMessages(session.id).lastOrNull()?.let { msg ->
                                msg.parts.filterIsInstance<TextPart>().lastOrNull()?.text
                            }
                        }.getOrNull()?.takeIf { it.isNotBlank() }?.take(NetworkConfig.previewTextMaxLength) ?: return@withPermit
                        _state.update { s -> s.copy(previews = s.previews + (session.id to preview)) }
                    }
                }
            }.joinAll()
        }
    }

    fun createSession(onCreated: (String) -> Unit) {
        val conn = container.activeConnection.value ?: return
        viewModelScope.launch {
            runCatching { conn.repository.createSession() }
                .onSuccess { onCreated(it.id); refresh() }
                .onFailure {
                    _state.update { it.copy(error = null) }
                    _transientError.value = container.friendlyError(it)
                }
        }
    }

    fun deleteSession(session: Session) {
        val conn = container.activeConnection.value ?: return
        viewModelScope.launch {
            runCatching { conn.repository.deleteSession(session.id) }
                .onSuccess {
                    container.draftStore.remove(session.id)
                    refresh()
                }
                .onFailure {
                    _state.update { it.copy(error = null) }
                    _transientError.value = container.friendlyError(it)
                }
        }
    }

    fun renameSession(session: Session, newTitle: String) {
        val conn = container.activeConnection.value ?: return
        val title = newTitle.trim()
        if (title.isEmpty() || title == session.title) return
        viewModelScope.launch {
            runCatching { conn.api.updateSession(session.id, title) }
                .onSuccess { refresh() }
                .onFailure {
                    _state.update { it.copy(error = null) }
                    _transientError.value = container.friendlyError(it)
                }
        }
    }

    /** Quick-switch to a different saved server without leaving the session list. */
    fun switchServer(profile: ServerProfile) {
        if (profile.id == activeProfileId) return
        if (_switchingId.value != null) return
        _switchingId.value = profile.id
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val conn = container.connect(profile)
                conn.api.ping()
            }
            _switchingId.value = null
            result.onSuccess { refresh() }
                .onFailure {
                    container.disconnect()
                    _state.value = SessionListState(loading = false, error = container.friendlyError(it))
                }
        }
    }
}
