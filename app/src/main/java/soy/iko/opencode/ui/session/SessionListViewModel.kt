package soy.iko.opencode.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.SessionDeleted
import soy.iko.opencode.data.model.SessionUpdated
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            container.activeConnection.collect { conn ->
                if (conn == null) return@collect
                conn.events.events.collect { event ->
                    when (event) {
                        is SessionUpdated -> {
                            val session = event.properties.info
                            val current = _state.value.sessions
                            val updated = (current.filterNot { it.id == session.id } + session)
                                .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0 }
                            _state.value = _state.value.copy(sessions = updated)
                            loadPreview(session.id)
                        }
                        is SessionDeleted -> {
                            val id = event.properties.info?.id ?: event.properties.sessionID
                            if (id != null) {
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
        _state.value = _state.value.copy(query = query)
    }

    fun refresh() {
        val conn = container.activeConnection.value
        if (conn == null) {
            if (_state.value.sessions.isEmpty()) {
                _state.value = SessionListState(loading = false, error = container.string(R.string.not_connected))
            } else {
                _state.value = _state.value.copy(loading = false)
                _transientError.value = container.string(R.string.not_connected)
            }
            return
        }
        _serverLabel.value = conn.profile.displayLabel
        _state.value = _state.value.copy(loading = true, error = null)
        _refreshing.value = true
        viewModelScope.launch {
            runCatching { conn.repository.listSessions() }
                .onSuccess { list ->
                    val sorted = list.sortedByDescending { it.time?.updated ?: it.time?.created ?: 0 }
                    _state.value = _state.value.copy(sessions = sorted, loading = false, error = null)
                    loadPreviews(sorted)
                }
                .onFailure {
                    if (_state.value.sessions.isNotEmpty()) {
                        _state.value = _state.value.copy(loading = false)
                        _transientError.value = container.friendlyError(it)
                    } else {
                        _state.value = SessionListState(loading = false, error = container.friendlyError(it))
                    }
                }
            _refreshing.value = false
        }
    }

    /** Reload the preview for a single session (used by the live SSE handler). */
    private fun loadPreview(sessionId: String) {
        val conn = container.activeConnection.value ?: return
        val api = conn.api
        viewModelScope.launch {
            val preview = runCatching {
                api.listMessages(sessionId).lastOrNull()?.let { msg ->
                    msg.parts.filterIsInstance<TextPart>().lastOrNull()?.text
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.take(200) ?: return@launch
            _state.update { s -> s.copy(previews = s.previews + (sessionId to preview)) }
        }
    }

    /** Fetch the last text part of each session for list previews (best-effort, bounded). */
    private fun loadPreviews(sessions: List<Session>) {
        previewJob?.cancel()
        val conn = container.activeConnection.value ?: return
        val api = conn.api
        _state.update { it.copy(previews = emptyMap()) }
        previewJob = viewModelScope.launch {
            val semaphore = Semaphore(MAX_CONCURRENT_PREVIEWS)
            sessions.take(50).map { session ->
                launch {
                    semaphore.withPermit {
                        val preview = runCatching {
                            api.listMessages(session.id).lastOrNull()?.let { msg ->
                                msg.parts.filterIsInstance<TextPart>().lastOrNull()?.text
                            }
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withPermit
                        _state.update { s -> s.copy(previews = s.previews + (session.id to preview)) }
                    }
                }
            }.joinAll()
        }
    }

    private companion object {
        const val MAX_CONCURRENT_PREVIEWS = 8
    }

    fun createSession(onCreated: (String) -> Unit) {
        val conn = container.activeConnection.value ?: return
        viewModelScope.launch {
            runCatching { conn.repository.createSession() }
                .onSuccess { onCreated(it.id); refresh() }
                .onFailure {
                    _state.value = _state.value.copy(error = null)
                    _transientError.value = container.friendlyError(it)
                }
        }
    }

    fun deleteSession(session: Session) {
        val conn = container.activeConnection.value ?: return
        viewModelScope.launch {
            runCatching { conn.repository.deleteSession(session.id) }
                .onSuccess { refresh() }
                .onFailure {
                    _state.value = _state.value.copy(error = null)
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
                    _state.value = _state.value.copy(error = null)
                    _transientError.value = container.friendlyError(it)
                }
        }
    }

    /** Quick-switch to a different saved server without leaving the session list. */
    fun switchServer(profile: ServerProfile) {
        if (profile.id == activeProfileId) return
        if (_switchingId.value != null) return
        _switchingId.value = profile.id
        _state.value = _state.value.copy(loading = true, error = null)
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
                    refresh()
                }
        }
    }
}
