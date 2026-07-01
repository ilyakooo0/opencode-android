package soy.iko.opencode.ui.session

import androidx.compose.runtime.Immutable
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
import soy.iko.opencode.util.runCatchingCancellable
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

@Immutable
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
                started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
                initialValue = EventStreamClient.ConnectionState.Disconnected,
            )

    /** Sessions with activity that arrived while not being viewed. */
    val unread: StateFlow<Set<String>> = container.unread

    val profiles: StateFlow<List<ServerProfile>> =
        container.profileStore.profiles.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
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
    /** One-shot transient errors surfaced as snackbars. A SharedFlow (not StateFlow) so
     *  each emission is delivered independently — two rapid failures both get a snackbar
     *  instead of the second silently overwriting the first. */
    private val _transientErrors = MutableSharedFlow<String>(
        extraBufferCapacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val transientErrors: SharedFlow<String> = _transientErrors.asSharedFlow()

    private var previewJob: Job? = null
    private var refreshJob: Job? = null

    /** Per-session in-flight preview loads, so rapid SSE events for the same session
     *  coalesce into a single request instead of firing one per event. Accessed from
     *  multiple coroutines (the SSE event handler and [loadPreview]), so the
     *  remove-then-put sequence is guarded by [previewLock] to stay atomic. */
    private val livePreviewJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val previewLock = Any()

    /** Accumulates SessionUpdated events between debounce flushes so a burst of SSE
     *  updates during active streaming coalesces into one list rebuild + recomposition
     *  instead of one per event. */
    private val pendingSessionUpdates = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private var sessionUpdateJob: Job? = null

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
                _serverLabel.value = conn.profile.displayLabel
                // Cancel any in-flight preview loads from the previous connection so
                // they don't complete against the old server and write stale previews
                // for sessions that may not exist on the new one. collectLatest cancels
                // the SSE collector, but the jobs in livePreviewJobs are independent
                // launches on viewModelScope that survive the collector cancellation.
                synchronized(previewLock) {
                    livePreviewJobs.values.forEach { it.cancel() }
                    livePreviewJobs.clear()
                }
                sessionUpdateJob?.cancel()
                pendingSessionUpdates.clear()
                // Reload the session list when a connection becomes available so the
                // initial load (which may have run before auto-reconnect finished and
                // found no connection) doesn't leave a stale "not connected" error.
                refresh()
                try {
                    conn.events.events.collect { event ->
                        when (event) {
                            is SessionUpdated -> {
                                val session = event.properties.info
                                pendingSessionUpdates[session.id] = session
                                if (sessionUpdateJob?.isActive != true) {
                                    sessionUpdateJob = viewModelScope.launch {
                                        delay(NetworkConfig.sessionUpdateDebounceMs)
                                        flushPendingSessionUpdates()
                                    }
                                }
                            }
                            is SessionDeleted -> {
                                val id = event.properties.info?.id ?: event.properties.sessionID
                                if (id != null) {
                                    synchronized(previewLock) { livePreviewJobs.remove(id)?.cancel() }
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SessionListVM", "SSE event collector error", e)
                }
            }
        }
    }

    /** Apply all buffered SessionUpdated events at once: upsert into the sessions list,
     *  re-sort by recency, and kick off preview fetches. Called after the debounce delay. */
    private fun flushPendingSessionUpdates() {
        val batch = pendingSessionUpdates.toMap()
        if (batch.isEmpty()) return
        pendingSessionUpdates.clear()
        _state.update { s ->
            val byId = s.sessions.associateBy { it.id }.toMutableMap()
            batch.forEach { (id, session) -> byId[id] = session }
            s.copy(
                sessions = byId.values
                    .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0 }
                    .toList(),
            )
        }
        batch.keys.forEach { loadPreview(it) }
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
                _transientErrors.tryEmit(container.string(R.string.not_connected))
            }
            _refreshing.value = false
            return
        }
        _serverLabel.value = conn.profile.displayLabel
        _state.update { it.copy(loading = true, error = null) }
        _refreshing.value = true
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            try {
                runCatchingCancellable { conn.repository.listSessions() }
                    .onSuccess { list ->
                        val sorted = list.sortedByDescending { it.time?.updated ?: it.time?.created ?: 0 }
                        _state.update { it.copy(sessions = sorted, loading = false, error = null) }
                        loadPreviews(sorted)
                    }
                    .onFailure {
                        if (_state.value.sessions.isNotEmpty()) {
                            _state.update { it.copy(loading = false) }
                            _transientErrors.tryEmit(container.friendlyError(it))
                        } else {
                            _state.value = SessionListState(loading = false, error = container.friendlyError(it))
                        }
                    }
            } finally {
                // Only clear the spinner if we're still the active refresh job. When a
                // newer refresh() cancels this one, its finally runs asynchronously on
                // the Main dispatcher and would otherwise clobber _refreshing back to
                // false while the newer refresh is still in flight.
                if (refreshJob === self) _refreshing.value = false
            }
        }
    }

    /**
     * Reload the preview for a single session (used by the live SSE handler).
     * Cancels any prior in-flight load for the same session so a burst of
     * [SessionUpdated] events coalesces into a single request. A short debounce
     * delay before the fetch avoids launching one full-history download per event
     * during active streaming — the fetch runs only after events quiet down.
     */
    private fun loadPreview(sessionId: String) {
        val conn = container.activeConnection.value ?: return
        val api = conn.api
        synchronized(previewLock) {
            livePreviewJobs.remove(sessionId)?.cancel()
            livePreviewJobs[sessionId] = viewModelScope.launch {
                val job = coroutineContext[kotlinx.coroutines.Job]
                try {
                    delay(NetworkConfig.previewDebounceMs)
                    val preview = fetchSessionPreview(api, sessionId) ?: return@launch
                    _state.update { s -> s.copy(previews = s.previews + (sessionId to preview)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SessionListVM", "Preview load failed for $sessionId", e)
                } finally {
                    if (job != null) synchronized(previewLock) { livePreviewJobs.remove(sessionId, job) }
                }
            }
        }
    }

    /** Fetch the last text part of each session for list previews (best-effort, bounded).
     *  Sessions that already have a cached preview are skipped — their previews are kept
     *  fresh by the live SSE handler ([loadPreview] on [SessionUpdated]). Each preview
     *  fetch downloads the full conversation history, so re-fetching all of them on every
     *  refresh is wasteful when most are already cached. */
    private fun loadPreviews(sessions: List<Session>) {
        previewJob?.cancel()
        val conn = container.activeConnection.value ?: return
        val api = conn.api
        // Keep previews for sessions still in the list; drop stale ones without
        // clearing the whole map (which would cause a visual flash).
        val keepIds = sessions.mapTo(mutableSetOf()) { it.id }
        _state.update { s -> s.copy(previews = s.previews.filterKeys { it in keepIds }) }
        val cached = _state.value.previews.keys
        val toFetch = sessions.take(NetworkConfig.maxPreviewSessions).filter { it.id !in cached }
        if (toFetch.isEmpty()) return
        previewJob = viewModelScope.launch {
            val semaphore = Semaphore(NetworkConfig.maxConcurrentPreviews)
            toFetch.map { session ->
                launch {
                    semaphore.withPermit {
                        val preview = fetchSessionPreview(api, session.id) ?: return@withPermit
                        _state.update { s -> s.copy(previews = s.previews + (session.id to preview)) }
                    }
                }
            }.joinAll()
        }
    }

    fun createSession(onCreated: (String) -> Unit) {
        val conn = container.activeConnection.value ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.repository.createSession() }
                .onSuccess { onCreated(it.id); refresh() }
                .onFailure {
                    _state.update { it.copy(error = null) }
                    _transientErrors.tryEmit(container.friendlyError(it))
                }
        }
    }

    fun deleteSession(session: Session) {
        val conn = container.activeConnection.value ?: return
        viewModelScope.launch {
            runCatchingCancellable { conn.repository.deleteSession(session.id) }
                .onSuccess {
                    container.draftStore.remove(session.id)
                    refresh()
                }
                .onFailure {
                    _state.update { it.copy(error = null) }
                    _transientErrors.tryEmit(container.friendlyError(it))
                }
        }
    }

    fun renameSession(session: Session, newTitle: String) {
        val conn = container.activeConnection.value ?: return
        val title = newTitle.trim()
        if (title.isEmpty() || title == session.title) return
        viewModelScope.launch {
            runCatchingCancellable { conn.api.updateSession(session.id, title) }
                .onSuccess { refresh() }
                .onFailure {
                    _state.update { it.copy(error = null) }
                    _transientErrors.tryEmit(container.friendlyError(it))
                }
        }
    }

    /** Quick-switch to a different saved server without leaving the session list. */
    fun switchServer(profile: ServerProfile) {
        if (profile.id == activeProfileId) return
        if (_switchingId.value != null) return
        val previousProfile = container.activeConnection.value?.profile
        val savedUnread = container.unread.value
        _switchingId.value = profile.id
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = runCatchingCancellable {
                val conn = container.connect(profile)
                conn.api.ping()
            }
            _switchingId.value = null
            result.onSuccess { refresh() }
                .onFailure { error ->
                    // connect() already closed the old connection; try to restore the
                    // previous profile so the session list survives. If there was none,
                    // show the error state.
                    container.disconnect()
                    if (previousProfile != null) {
                        val restored = runCatchingCancellable { container.connect(previousProfile) }
                        // connect() clears unread state; restore what we saved so the
                        // user doesn't lose unread badges on a failed server switch.
                        savedUnread.forEach { container.restoreUnread(it) }
                        if (restored.isSuccess) {
                            // Clear stale sessions from the old server; the SSE observer
                            // will reload from the restored connection via refresh().
                            _state.update {
                                SessionListState(
                                    sessions = emptyList(),
                                    previews = emptyMap(),
                                    loading = false,
                                )
                            }
                            // Surface the switch failure as a transient snackbar — refresh()
                            // would clear a state.error, so use transientErrors which survives it.
                            _transientErrors.tryEmit(container.friendlyError(error))
                            refresh()
                        } else {
                            // Restore also failed — the user is now disconnected from
                            // both servers. Surface both failures so the user understands
                            // why they're looking at an empty list.
                            val restoreMsg = container.friendlyError(restored.exceptionOrNull() ?: error)
                            _state.value = SessionListState(
                                loading = false,
                                error = container.string(R.string.error_switch_and_restore_failed,
                                    container.friendlyError(error), restoreMsg),
                            )
                        }
                    } else {
                        _state.value = SessionListState(loading = false, error = container.friendlyError(error))
                    }
                }
        }
    }
}

/**
 * Fetch the last text part of a session's most recent message, truncated to
 * [NetworkConfig.previewTextMaxLength]. Returns null on failure or when the
 * last message has no text part.
 */
private suspend fun fetchSessionPreview(
    api: soy.iko.opencode.data.network.OpencodeApiClient,
    sessionId: String,
): String? = runCatchingCancellable {
    api.listMessages(sessionId).lastOrNull()?.let { msg ->
        msg.parts.filterIsInstance<TextPart>().lastOrNull()?.text
    }
}.getOrNull()?.takeIf { it.isNotBlank() }?.take(NetworkConfig.previewTextMaxLength)
