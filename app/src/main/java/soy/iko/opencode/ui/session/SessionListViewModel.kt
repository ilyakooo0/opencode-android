package soy.iko.opencode.ui.session

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.SessionDeleted
import soy.iko.opencode.data.model.SessionUpdated
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.ToolCompleted
import soy.iko.opencode.data.model.ToolError
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.ToolRunning
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

/** Sort order for the session list. */
enum class SessionSortMode { RECENT, TITLE }

/** Directory options for the new-session picker: worktree paths the server knows about
 *  ([projects]) and the server's default working directory ([serverDefault], its cwd).
 *  [loaded] distinguishes "not fetched yet" from "fetched, none found". */
@Immutable
data class DirectoryOptionsState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val projects: List<String> = emptyList(),
    val serverDefault: String? = null,
)

@Immutable
data class SessionListState(
    val sessions: List<Session> = emptyList(),
    val query: String = "",
    val previews: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
    val sortMode: SessionSortMode = SessionSortMode.RECENT,
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

/** Sort sessions by the given mode. RECENT sorts by last activity time desc; TITLE by
 *  display title asc (case-insensitive), falling back to recency for ties. */
private fun Iterable<Session>.sortedByMode(mode: SessionSortMode): List<Session> = when (mode) {
    SessionSortMode.RECENT ->
        sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
    SessionSortMode.TITLE ->
        // CASE_INSENSITIVE_ORDER avoids allocating a lowercased title per comparison; this
        // sort re-runs on every debounced list flush during streaming.
        sortedWith(
            compareBy<Session, String>(String.CASE_INSENSITIVE_ORDER) { it.displayTitle }
                .thenByDescending { it.time?.updated ?: it.time?.created ?: 0L },
        )
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

    /** Sessions with activity that arrived while not being viewed, mapped to their
     *  unread event count so the list can show "N unread" instead of a bare dot. */
    val unread: StateFlow<Map<String, Int>> = container.unread

    val profiles: StateFlow<List<ServerProfile>> =
        container.profileStore.profiles.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(NetworkConfig.stateFlowSubscriptionTimeoutMs),
            initialValue = emptyList(),
        )

    private val _serverLabel = MutableStateFlow(
        container.activeConnection.value?.profile?.displayLabel ?: container.string(R.string.app_name)
    )
    val serverLabel: StateFlow<String> = _serverLabel.asStateFlow()

    private val _switchingId = MutableStateFlow<String?>(null)
    val switchingId: StateFlow<String?> = _switchingId.asStateFlow()

    /** True while a session creation is in flight, so the FAB can disable itself and
     *  prevent double-taps from firing two createSession calls. */
    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

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

    /** One-shot events carrying the id of a session marked for deferred deletion, so the
     *  UI can show an Undo snackbar. */
    private val _undoEvents = MutableSharedFlow<String>(
        extraBufferCapacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val undoEvents: SharedFlow<String> = _undoEvents.asSharedFlow()

    /** Session awaiting the undo window to expire before its REST delete fires. Holds the
     *  Session so Undo can restore it. */
    // Keyed by session id, not a single field: the undo window lets several deletes be
    // in flight at once, and a single field would let a second delete overwrite the
    // first — dropping the first's server-side delete while it stays removed from the UI.
    // Concurrent because the delete timer's completion callbacks run on the container's
    // process-lived scope while the SSE collector mutates this on viewModelScope.
    private val pendingDeletes = java.util.concurrent.ConcurrentHashMap<String, Session>()

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
                // Cancel any deferred deletes still pending from the previous connection.
                // Their captured connection is now closed, so they can only fail; letting
                // them run would fire onError and re-inject a session belonging to the old
                // server into this (new) server's list. Cancelling drops them cleanly — the
                // session simply survives on its original server (unavoidable once we've
                // switched away) rather than appearing as a foreign row here.
                pendingDeletes.keys.toList().forEach { container.cancelSessionDelete(it) }
                pendingDeletes.clear()
                // Reload the session list when a connection becomes available so the
                // initial load (which may have run before auto-reconnect finished and
                // found no connection) doesn't leave a stale "not connected" error.
                refresh()
                try {
                    conn.events.events.collect { event ->
                        when (event) {
                            is SessionUpdated -> {
                                val session = event.properties.info
                                // Ignore updates for a session the user just deleted (still
                                // within its undo window): the server hasn't deleted it yet
                                // and can legitimately emit updates, but buffering them here
                                // would resurrect the optimistically-hidden row mid-undo.
                                if (pendingDeletes.containsKey(session.id)) return@collect
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
                                    // Drop any buffered update for this id so a pending flush
                                    // can't re-add the just-deleted session to the list.
                                    pendingSessionUpdates.remove(id)
                                    // The session is gone server-side; cancel any pending local
                                    // delete so its deferred REST call doesn't later 404 and
                                    // trigger the failure-restore path that resurrects the row.
                                    pendingDeletes.remove(id)
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
        // Remove only the snapshotted key/value pairs rather than clear()-ing the whole
        // map: an update for a session that arrived between the toMap() snapshot and here
        // would otherwise be dropped. The key/value remove overload also leaves a newer
        // value for the same key in place to be flushed on the next pass.
        batch.forEach { (k, v) -> pendingSessionUpdates.remove(k, v) }
        val changedIds = mutableSetOf<String>()
        _state.update { s ->
            // Reset per-attempt: MutableStateFlow.update may re-run this lambda on CAS
            // contention, and we only want the ids that changed in the winning attempt.
            changedIds.clear()
            val byId = s.sessions.associateBy { it.id }.toMutableMap()
            batch.forEach { (id, session) ->
                // Final guard: never re-add a session that's pending deletion (its undo
                // window is still open), even if an update slipped into this batch.
                if (pendingDeletes.containsKey(id)) return@forEach
                if (byId[id] != session) { byId[id] = session; changedIds += id }
            }
            // No-op update: skip the rebuild entirely so the StateFlow doesn't emit a
            // new state (and trigger recomposition) when the batch held no real changes
            // (e.g. a duplicate SessionUpdated for an unchanged session).
            if (changedIds.isEmpty()) return@update s
            val sorted = byId.values.sortedByMode(s.sortMode)
            // If the new ordering matches the existing one (e.g. only the already-newest
            // session updated its timestamp), reuse the same list instance so downstream
            // skips a recomposition for an identical reference.
            val sessions = if (sorted == s.sessions) s.sessions else sorted
            s.copy(sessions = sessions)
        }
        // Only refetch previews for sessions that actually changed — a duplicate
        // SessionUpdated for an unchanged session shouldn't trigger a full-history download.
        changedIds.forEach { loadPreview(it) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** Force the SSE stream to reconnect (recovery path for a Failed banner) and
     *  re-fetch the session list so the UI reflects the current server state. */
    fun retryConnection() {
        container.activeConnection.value?.events?.triggerReconnect()
        refresh()
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
        // Only show the full-screen spinner on the initial load (empty list). For a
        // pull-to-refresh or SSE-reconnect refresh with an existing list, the dedicated
        // PullToRefreshBox indicator (_refreshing) covers it — flipping `loading` here
        // would blank the populated list behind a centered spinner.
        _state.update { it.copy(loading = it.sessions.isEmpty(), error = null) }
        _refreshing.value = true
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            try {
                runCatchingCancellable { conn.repository.listSessions() }
                    .onSuccess { list ->
                        // Drop sessions that were optimistically deleted but whose deferred
                        // REST delete hasn't fired yet — the server still returns them, and
                        // re-adding them here would resurrect a just-hidden row (and later
                        // collide on the LazyColumn id key when undo/restore re-appends it).
                        val sorted = list.filterNot { pendingDeletes.containsKey(it.id) }.sortedByMode(_state.value.sortMode)
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
        val toolFormat = container.string(R.string.tool_preview)
        synchronized(previewLock) {
            livePreviewJobs.remove(sessionId)?.cancel()
            livePreviewJobs[sessionId] = viewModelScope.launch {
                val job = coroutineContext[kotlinx.coroutines.Job]
                try {
                    delay(NetworkConfig.previewDebounceMs)
                    val preview = fetchSessionPreview(api, sessionId, toolFormat) ?: return@launch
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
        val toolFormat = container.string(R.string.tool_preview)
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
                        val preview = fetchSessionPreview(api, session.id, toolFormat) ?: return@withPermit
                        _state.update { s -> s.copy(previews = s.previews + (session.id to preview)) }
                    }
                }
            }.joinAll()
        }
    }

    /**
     * Create a new session, optionally in a specific worktree [directory] (the agent's
     * working directory for that session). A null/blank directory lets the server use its
     * launch cwd — the pre-directory behavior. The chosen directory is remembered
     * ([lastChosenDirectory]) so the next new-session picker can preselect it.
     */
    fun createSession(directory: String? = null, onCreated: (String) -> Unit) {
        val conn = container.activeConnection.value ?: return
        if (!_creating.compareAndSet(false, true)) return
        val dir = directory?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            try {
                runCatchingCancellable { conn.repository.createSession(directory = dir) }
                    .onSuccess { lastChosenDirectory = dir; onCreated(it.id); refresh() }
                    .onFailure {
                        _state.update { it.copy(error = null) }
                        _transientErrors.tryEmit(container.friendlyError(it))
                    }
            } finally {
                _creating.value = false
            }
        }
    }

    /** The directory the user last created a session in (this VM lifetime), so the picker
     *  can preselect it. Best-effort: not persisted across process death. */
    var lastChosenDirectory: String? = null
        private set

    private val _directoryOptions = MutableStateFlow(DirectoryOptionsState())
    val directoryOptions: StateFlow<DirectoryOptionsState> = _directoryOptions.asStateFlow()

    private var directoryOptionsJob: Job? = null

    /**
     * Load the directory options for the new-session picker: the server's known projects
     * (`GET /project`) plus its default working directory (`GET /path`). Best-effort — a
     * failure leaves an empty option set, and the picker still offers the directories of
     * existing sessions and manual path entry. Safe to call on each dialog open; an
     * in-flight load is not restarted.
     */
    fun loadDirectoryOptions() {
        val conn = container.activeConnection.value ?: return
        if (_directoryOptions.value.loading) return
        _directoryOptions.update { it.copy(loading = true) }
        directoryOptionsJob = viewModelScope.launch {
            val projects = runCatchingCancellable { conn.api.listProjects() }
                .getOrDefault(emptyList())
                .mapNotNull { it.worktree.takeIf { w -> w.isNotBlank() } }
                .distinct()
            val serverDefault = runCatchingCancellable { conn.api.currentPath() }
                .getOrNull()?.directory?.takeIf { it.isNotBlank() }
            _directoryOptions.value = DirectoryOptionsState(
                loading = false,
                loaded = true,
                projects = projects,
                serverDefault = serverDefault,
            )
        }
    }

    /**
     * Mark [session] as pending deletion and hide it from the list immediately, then
     * surface an Undo via [undoEvents]. The actual REST delete is deferred by
     * [NetworkConfig.undoDeleteDelayMs]; if [undoDelete] is called before it fires,
     * the session is restored. Otherwise the delete proceeds.
     */
    fun deleteSession(session: Session) {
        // Optimistically remove from the visible list so the UI feels instant.
        _state.update { s ->
            s.copy(sessions = s.sessions.filterNot { it.id == session.id })
        }
        // Hold the pending delete so the snackbar can undo it.
        pendingDeletes[session.id] = session
        // Drop any already-buffered update for this session so a pending flush during the
        // undo window can't re-add the row we just optimistically hid.
        pendingSessionUpdates.remove(session.id)
        _undoEvents.tryEmit(session.id)
        // Run the deferred delete on the container's process-lived scope, not viewModelScope:
        // if this VM is cleared during the undo window (e.g. a disconnect pops the session
        // list), a viewModelScope job would be cancelled and the server-side delete would
        // never fire — the row was already optimistically hidden, so the session would
        // silently resurrect on the next refresh. The container also clears the stored draft.
        container.scheduleSessionDelete(
            id = session.id,
            delayMs = NetworkConfig.undoDeleteDelayMs,
            onDeleted = {
                pendingDeletes.remove(session.id)
                refresh()
            },
            onError = {
                // Restore the session since the delete failed (or there was no connection to
                // delete on). Guard against a concurrent refresh having already re-added it so
                // we never produce two rows with the same id.
                pendingDeletes.remove(session.id)
                _state.update { s ->
                    s.copy(sessions = (s.sessions.filterNot { it.id == session.id } + session).sortedByMode(s.sortMode))
                }
                _transientErrors.tryEmit(container.friendlyError(it))
            },
        )
    }

    /** Cancel a pending delete (Undo snackbar action) and restore the session. */
    fun undoDelete(sessionId: String) {
        // Cancel the deferred container-scoped delete first. If it already fired (cancel
        // returns false), the delete is committing/committed — drop the token and don't
        // restore, so we never re-add a session that's gone on the server.
        if (!container.cancelSessionDelete(sessionId)) {
            pendingDeletes.remove(sessionId)
            return
        }
        val pending = pendingDeletes.remove(sessionId) ?: return
        _state.update { s ->
            // Dedup by id in case a refresh during the undo window already re-added it.
            s.copy(sessions = (s.sessions.filterNot { it.id == pending.id } + pending).sortedByMode(s.sortMode))
        }
    }

    fun setSortMode(mode: SessionSortMode) {
        if (mode == _state.value.sortMode) return
        _state.update { s -> s.copy(sortMode = mode, sessions = s.sessions.sortedByMode(mode)) }
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
                        // Pass the real captured count so a session badged with "5
                        // unread" is restored as 5, not 1.
                        savedUnread.forEach { (id, count) -> container.restoreUnread(id, count) }
                        if (restored.isSuccess) {
                            // Clear stale sessions from the old server; the SSE observer
                            // will reload from the restored connection via refresh(). Preserve
                            // the user's sort mode and search query — the switch failed and they
                            // were bounced back to the same server, so resetting those (via a
                            // fresh SessionListState) would be an unexpected loss on an error path.
                            _state.update {
                                it.copy(
                                    sessions = emptyList(),
                                    previews = emptyMap(),
                                    loading = false,
                                    error = null,
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
 * Fetch a preview of a session's most recent activity, truncated to
 * [NetworkConfig.previewTextMaxLength]. Returns the last text part if present; otherwise
 * a short summary of the last tool call (e.g. "🔧 read") so a session whose last activity
 * was a tool call (no assistant text yet) still shows a meaningful preview instead of
 * stale/empty. Returns null on failure or when there's nothing usable.
 *
 * [toolPreviewFormat] is a format string (from resources) with a %1$s placeholder for
 * the tool title/name, so the wrench emoji prefix is a presentation concern resolved
 * by the caller rather than hardcoded here.
 */
private suspend fun fetchSessionPreview(
    api: soy.iko.opencode.data.network.OpencodeApiClient,
    sessionId: String,
    toolPreviewFormat: String,
): String? = runCatchingCancellable {
    api.listMessages(sessionId).lastOrNull()?.let { msg ->
        val text = msg.parts.filterIsInstance<TextPart>().lastOrNull()?.text
        if (text != null) return@let text
        val tool = msg.parts.filterIsInstance<ToolPart>().lastOrNull()
        if (tool != null) {
            val title = when (val st = tool.state) {
                is ToolRunning -> st.title
                is ToolCompleted -> st.title
                is ToolError -> st.error
                else -> null
            }
            return@let title?.takeIf { it.isNotBlank() }?.let { toolPreviewFormat.format(it) }
                ?: toolPreviewFormat.format(tool.tool)
        }
        null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }?.take(NetworkConfig.previewTextMaxLength)
