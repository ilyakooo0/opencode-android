package soy.iko.opencode.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.data.model.FilePromptPart
import soy.iko.opencode.data.model.MessagePartUpdated
import soy.iko.opencode.data.model.MessageUpdated
import soy.iko.opencode.data.model.Permission
import soy.iko.opencode.data.model.PermissionReplied
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.model.PermissionUpdated
import soy.iko.opencode.data.model.StepFinishPart
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.SessionError
import soy.iko.opencode.data.model.SessionIdle
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.TODO_WRITE_TOOL
import soy.iko.opencode.data.model.parseTodos
import soy.iko.opencode.data.model.statusEnum
import soy.iko.opencode.data.model.TodoStatus
import soy.iko.opencode.data.model.inputElement
import soy.iko.opencode.data.repo.AttachmentDraftStore
import soy.iko.opencode.data.repo.BackupManager
import soy.iko.opencode.data.repo.DraftStore
import soy.iko.opencode.data.repo.ErrorKind
import soy.iko.opencode.data.repo.FileBrowserPrefs
import soy.iko.opencode.data.repo.MessageCacheStore
import soy.iko.opencode.data.repo.OutboxMessage
import soy.iko.opencode.data.repo.OutboxStore
import soy.iko.opencode.data.repo.RecentModelsStore
import soy.iko.opencode.data.repo.SearchHistoryStore
import soy.iko.opencode.data.repo.SessionPrefsStore
import soy.iko.opencode.data.repo.SessionRepository
import soy.iko.opencode.data.repo.ProfileStore
import soy.iko.opencode.data.repo.SettingsStore
import soy.iko.opencode.data.repo.classifyError
import soy.iko.opencode.data.repo.responseStatusCode
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.HttpClientFactory
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.data.network.OpencodeApiClient
import soy.iko.opencode.notification.NotificationChannels
import soy.iko.opencode.notification.RunForegroundService
import soy.iko.opencode.util.safeExceptionSummary
import soy.iko.opencode.notification.SessionNotifications
import soy.iko.opencode.R
import soy.iko.opencode.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of probing a server URL to check connectivity and whether authentication
 * is required, without persisting a profile or opening a long-lived connection.
 */
sealed class ProbeResult {
    /** Server is reachable and accepts unauthenticated requests. */
    object Reachable : ProbeResult()

    /** Server is reachable but rejected the probe with an authentication error. */
    object NeedsAuth : ProbeResult()

    /** Server could not be reached (host unresolved, connection refused, timeout, etc.). */
    data class Unreachable(val error: String) : ProbeResult()
}

/**
 * Hand-written service locator held by [soy.iko.opencode.OpencodeApp]. Owns the
 * process-wide singletons and the currently active [OpencodeConnection].
 */
open class AppContainer private constructor(
    private val appContext: Context?,
    private val skipInit: Boolean,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(context: Context) : this(context.applicationContext, false, false)
    protected constructor() : this(null, true, true)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    open val profileStore: ProfileStore by lazy { ProfileStore(appContext!!) }
    open val settingsStore: SettingsStore by lazy { SettingsStore(appContext!!) }
    open val draftStore: DraftStore by lazy { DraftStore(appContext!!, appScope) }
    open val attachmentDraftStore: AttachmentDraftStore by lazy { AttachmentDraftStore(appContext!!) }
    open val sessionPrefsStore: SessionPrefsStore by lazy { SessionPrefsStore(appContext!!) }
    open val messageCacheStore: MessageCacheStore by lazy { MessageCacheStore(appContext!!) }
    open val outboxStore: OutboxStore by lazy { OutboxStore(appContext!!) }
    open val searchHistoryStore: SearchHistoryStore by lazy { SearchHistoryStore(appContext!!) }
    open val recentModelsStore: RecentModelsStore by lazy { RecentModelsStore(appContext!!) }
    open val fileBrowserPrefs: FileBrowserPrefs by lazy { FileBrowserPrefs(appContext!!) }
    open val backupManager: BackupManager by lazy { BackupManager(profileStore, settingsStore, sessionPrefsStore) }

    /** True while the outbox is actively flushing queued messages to the server, so the UI
     *  can show a "sending queued messages…" indicator. */
    private val _outboxSending = MutableStateFlow(false)
    open val outboxSending: StateFlow<Boolean> = _outboxSending.asStateFlow()

    /** Bumped to nudge an immediate outbox flush (after enqueue, or a manual "Send now"),
     *  independent of connection/online changes. */
    private val _outboxFlushTrigger = MutableStateFlow(0)
    open fun flushOutbox() { _outboxFlushTrigger.update { it + 1 } }

    private val _activeConnection = MutableStateFlow<OpencodeConnection?>(null)
    open val activeConnection: StateFlow<OpencodeConnection?> = _activeConnection.asStateFlow()

    /** True while a background auto-reconnect to the most-recent server is in flight. */
    private val _reconnecting = MutableStateFlow(false)
    open val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    /**
     * Set once a startup auto-reconnect succeeds. The server list collects this and
     * (once) navigates straight to the session list so a returning user skips it.
     * [consumeAutoConnect] guards against re-firing on screen re-entry.
     */
    private val _autoConnectDone = MutableStateFlow(false)
    open val autoConnectDone: StateFlow<Boolean> = _autoConnectDone.asStateFlow()
    private val autoConnectConsumed = AtomicBoolean(false)
    open fun consumeAutoConnect(): Boolean = autoConnectConsumed.compareAndSet(false, true)

    /** Text shared into the app (ACTION_SEND), prefilled into a session draft. */
    private val _pendingShare = MutableStateFlow<String?>(null)
    open val pendingShare: StateFlow<String?> = _pendingShare.asStateFlow()
    open fun setPendingShare(text: String) { _pendingShare.value = text }
    open fun consumePendingShare(): String? {
        val current = _pendingShare.value ?: return null
        return if (_pendingShare.compareAndSet(current, null)) current else null
    }

    /** Image/file Uris (as strings) shared into the app, staged as attachments in the next
     *  opened session. Consumed once by the first ChatScreen to compose after the share. */
    private val _pendingSharedMedia = MutableStateFlow<List<String>>(emptyList())
    open val pendingSharedMedia: StateFlow<List<String>> = _pendingSharedMedia.asStateFlow()
    open fun setPendingSharedMedia(uris: List<String>) { _pendingSharedMedia.value = uris }
    open fun consumePendingSharedMedia(): List<String> {
        val current = _pendingSharedMedia.value
        if (current.isEmpty()) return emptyList()
        return if (_pendingSharedMedia.compareAndSet(current, emptyList())) current else emptyList()
    }

    /**
     * Set from an external "New session" trigger (launcher shortcut / QS tile). The nav host
     * consumes it once connected, creating a fresh session and opening it. Uses a counter
     * (not a boolean) so re-tapping the shortcut while a prior request is still pending
     * increments the value and re-fires the LaunchedEffect — a boolean StateFlow wouldn't
     * emit when set to the same value, making the request unrecoverable by re-tapping.
     */
    private val _pendingNewSession = MutableStateFlow(0)
    open val pendingNewSession: StateFlow<Int> = _pendingNewSession.asStateFlow()
    open fun requestNewSession() { _pendingNewSession.update { it + 1 } }
    open fun consumePendingNewSession(): Boolean {
        while (true) {
            val current = _pendingNewSession.value
            if (current == 0) return false
            if (_pendingNewSession.compareAndSet(current, current - 1)) return true
        }
    }

    /** A one-shot signal to navigate to the Diagnostics screen (e.g. from the crash-relaunch
     *  prompt). Consumed by OpencodeApp's NavHost. */
    private val _pendingDiagnostics = MutableStateFlow(false)
    open val pendingDiagnostics: StateFlow<Boolean> = _pendingDiagnostics.asStateFlow()
    open fun requestDiagnostics() { _pendingDiagnostics.value = true }
    open fun consumePendingDiagnostics(): Boolean = _pendingDiagnostics.compareAndSet(true, false)

    /**
     * A session id to open from an external trigger (a notification tap or a deep link),
     * paired with the originating server's profile id (if known) so a tap after the user
     * has switched servers routes back to the server that ran the session. The nav host
     * consumes it once a connection is active (switching to [profileId] first if needed).
     */
    data class PendingOpenSession(val sessionId: String, val profileId: String?)

    private val _pendingOpenSession = MutableStateFlow<PendingOpenSession?>(null)
    open val pendingOpenSession: StateFlow<PendingOpenSession?> = _pendingOpenSession.asStateFlow()
    open fun requestOpenSession(id: String, profileId: String? = null) {
        _pendingOpenSession.value = PendingOpenSession(id, profileId)
    }
    open fun consumePendingOpenSession(): PendingOpenSession? {
        val current = _pendingOpenSession.value ?: return null
        return if (_pendingOpenSession.compareAndSet(current, null)) current else null
    }

    /**
     * A file path requested to be opened (via deep link `opencode://file/{path}` or future
     * entry points), consumed by the NavHost to navigate to the file viewer. Mirrors the
     * session open-signal pattern: a StateFlow the NavHost observes, drained atomically.
     */
    private val _pendingOpenFile = MutableStateFlow<String?>(null)
    open val pendingOpenFile: StateFlow<String?> = _pendingOpenFile.asStateFlow()
    open fun requestOpenFile(path: String) { _pendingOpenFile.value = path }
    open fun consumePendingOpenFile(): String? {
        val current = _pendingOpenFile.value ?: return null
        return if (_pendingOpenFile.compareAndSet(current, null)) current else null
    }

    /**
     * The session the user is currently viewing (or null when not in a chat). Drives the
     * unread tracker: messages arriving for any *other* session mark it unread, and
     * opening a session clears its unread state.
     */
    private val _currentSession = MutableStateFlow<String?>(null)
    open val currentSession: StateFlow<String?> = _currentSession.asStateFlow()

    /** Session ids that received activity while not being viewed, mapped to the count
     *  of unread message events. A count (not just a presence set) lets the session
     *  list badge show "3 unread" instead of a bare dot, so the user can tell whether
     *  one reply or a whole burst arrived. */
    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    open val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    /** Session ids the user has muted. A muted session doesn't badge unread (the count is
     *  still tracked internally so unmuting can restore it, but the badge and completion
     *  notification are suppressed). Persisted via SessionPrefsStore so the choice survives
     *  process death. */
    private val _mutedSessions = MutableStateFlow<Set<String>>(emptySet())
    open val mutedSessions: StateFlow<Set<String>> = _mutedSessions.asStateFlow()

    /** True when any assistant run is actively streaming across all sessions. Drives the
     *  disconnect confirmation on the session list (disconnecting mid-run kills it). */
    private val _anyRunActive = MutableStateFlow(false)
    open val anyRunActive: StateFlow<Boolean> = _anyRunActive.asStateFlow()

    /** Session ids currently streaming an assistant run, surfaced so the session list can
     *  badge the row whose run is active. Mirrors [activeRuns] as an immutable snapshot so
     *  Compose can skip recomposition when the set hasn't changed. */
    private val _runningSessionIds = MutableStateFlow<Set<String>>(emptySet())
    open val runningSessionIds: StateFlow<Set<String>> = _runningSessionIds.asStateFlow()

    /** Latest task-plan progress text for the active run (e.g. "Step 2 of 5"), surfaced in
     *  the foreground-service notification so the user can see what the agent is working on
     *  without opening the app. Null when no plan is available or no run is active. */
    private val _runProgressText = MutableStateFlow<String?>(null)
    open val runProgressText: StateFlow<String?> = _runProgressText.asStateFlow()

    /** Whether the device currently has network connectivity. Distinct from the SSE
     *  connection state so the UI can tell "you're offline" (device) from "server
     *  unreachable" (credentials/host). Updated by the [networkCallback]. */
    private val _isOnline = MutableStateFlow(true)
    open val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /** Whether a foreground Activity is currently showing. Distinct from [currentSession],
     *  which stays set while the app is merely backgrounded (the chat screen isn't disposed
     *  when the user locks their phone). Permission/completion notifications need the real
     *  foreground signal so they still fire for a session the user "has open" but has walked
     *  away from — the core of the run-in-the-background use case. Set from MainActivity's
     *  onStart/onStop. */
    private val _isForeground = MutableStateFlow(false)
    open val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()
    open fun setForeground(foreground: Boolean) { _isForeground.value = foreground }

    /** True when the user is actively looking at [sessionId] (app foregrounded AND that
     *  session is the one on screen), so an in-app affordance (the permission dialog) is
     *  handling it and a notification would be redundant. */
    private fun isActivelyViewing(sessionId: String): Boolean =
        _isForeground.value && sessionId == _currentSession.value

    open fun setCurrentSession(id: String?) {
        _currentSession.value = id
        if (id != null) {
            _unread.update { it - id }
            unreadMessageIds.remove(id)
            appContext?.let {
                SessionNotifications.cancel(it, id)
                SessionNotifications.cancelPermission(it, id)
                SessionNotifications.cancelError(it, id)
            }
        }
    }

    /** Clear unread badge and dedup set for a deleted session so its stale count doesn't
     *  linger in the session list badge or leak memory until the next connect/disconnect. */
    open fun clearUnread(id: String) {
        _unread.update { it - id }
        unreadMessageIds.remove(id)
    }

    /** Mark a session as read (clear its unread badge) — the "Mark read" notification action.
     *  Updates the launcher-icon badge directly (not just the in-memory [StateFlow]) so a
     *  "Mark read" tap while the app is backgrounded — when [SessionListScreen]'s
     *  `LaunchedEffect(totalUnread)` collector isn't running — still decrements the badge.
     *  Without this, the notification dismisses but the launcher badge shows a stale count
     *  until the app is next foregrounded. */
    open fun markSessionRead(id: String) {
        clearUnread(id)
        appContext?.let { ctx ->
            SessionNotifications.updateUnreadBadge(ctx, _unread.value.values.sum())
        }
    }

    /** Mark a session as unread with a count of 1 (or restore a prior count). Used by the
     *  session list's "Mark as unread" overflow action so a user can re-flag a conversation
     *  they want to revisit. No-op when the session is the one currently being viewed — a
     *  viewed session clears its own unread badge via [setCurrentSession], so marking it
     *  unread would race and immediately clear. Muted sessions are still marked (the count is
     *  tracked so unmuting restores it) but the list UI hides the badge while muted. */
    open fun markSessionUnread(id: String, count: Int = 1) {
        if (id == _currentSession.value) return
        _unread.update { it + (id to count) }
    }

    /** Toggle a session's mute state. A muted session suppresses the unread badge and the
     *  completion notification so a noisy conversation doesn't interrupt. Persisted
     *  via SessionPrefsStore so it survives process death. */
    fun toggleSessionMute(id: String) {
        // Use atomic update so a concurrent prefs reload (_mutedSessions.value = ids from
        // sessionPrefsStore.muted.collect) can't clobber this toggle mid read-modify-write.
        var nowMuted = false
        _mutedSessions.update { current ->
            nowMuted = id !in current
            if (nowMuted) current + id else current - id
        }
        appContext?.let {
            appScope.launch {
                runCatchingCancellable { sessionPrefsStore.setMuted(id, nowMuted) }
            }
        }
    }

    /** True when [id] is muted (unread badge and completion notification suppressed). */
    fun isSessionMuted(id: String): Boolean = id in _mutedSessions.value

    /** Clear every session's unread badge at once — the session list's "Mark all read" action. */
    open fun clearAllUnread() {
        _unread.update { emptyMap() }
    }

    /** Restore a session's unread badge after a failed server switch reconnects.
     *  Preserves the prior [count] so a session badged with "5 unread" before the
     *  switch attempt still shows 5 (not 1) after the restore. No-op for the session
     *  the user is currently viewing, since a viewed session is never badged. */
    open fun restoreUnread(id: String, count: Int) {
        if (id != _currentSession.value) {
            _unread.update { it + (id to count) }
        }
    }

    /** Resolve a localized string — view models reach resources through the container. */
    open fun string(id: Int, vararg formatArgs: Any): String {
        if (appContext == null) return ""
        return if (formatArgs.isEmpty()) appContext.getString(id) else appContext.getString(id, *formatArgs)
    }

    /**
     * Convert a throwable into a user-facing message. Classifies the error by concrete
     * type (network, timeout, HTTP status) rather than string-matching class names, so
     * the message reflects what actually went wrong without leaking internal URLs/state.
     */
    open fun friendlyError(t: Throwable): String =
        friendlyErrorFor(t, activeConnection.value?.profile?.baseUrl.orEmpty())

    /**
     * Same classification as [friendlyError] but accepts an explicit base URL, so callers
     * that aren't operating on the active connection (e.g. [probeServer]) can still
     * produce a user-facing message with the right server address.
     */
    open fun friendlyErrorFor(t: Throwable, baseUrl: String): String =
        when (classifyError(t)) {
            ErrorKind.NOT_REACHABLE, ErrorKind.NETWORK ->
                string(R.string.error_not_reachable, baseUrl)
            ErrorKind.TIMEOUT -> string(R.string.error_timeout)
            ErrorKind.SERVER -> string(R.string.error_server)
            // Show only the HTTP status (e.g. 401, 404) — never the request URL,
            // which a ClientRequestException carries in its message and which we
            // promised not to leak (it can include auth or internal paths).
            ErrorKind.CLIENT -> {
                val code = responseStatusCode(t)
                when (code) {
                    // Auth failures reuse the connection banner text: the request itself is
                    // fine, the credentials aren't, so the fix is re-editing the profile —
                    // not "request failed (401)" which implies an opaque program error.
                    401, 403 -> string(R.string.connection_failed)
                    404 -> string(R.string.error_not_found)
                    else -> code?.let { string(R.string.error_client_status, it) }
                        ?: string(R.string.error_generic)
                }
            }
            ErrorKind.UNKNOWN -> string(R.string.error_generic)
        }

    /**
     * Drive the [RunForegroundService] from the process-lived [appScope] rather than from the
     * Activity's composition. Previously the start/stop was wired to a
     * `collectAsStateWithLifecycle` collector in the root composable, which pauses while the
     * Activity is STOPPED — so a run *started while backgrounded* (e.g. via the notification
     * inline-reply) never acquired foreground priority and its long-lived SSE stream could be
     * killed by Doze, and a run that *ended* while backgrounded left the "working…"
     * notification lingering until the app was reopened. Collecting here reacts to
     * [anyRunActive] regardless of Activity state.
     */
    private fun observeRunForegroundService() {
        val ctx = appContext ?: return
        appScope.launch {
            // Debounce so a run that starts then fails/idles almost immediately doesn't
            // dispatch startForegroundService() then stopService() back-to-back — if the stop
            // wins that race before the service calls startForeground(), Android raises the
            // "did not then call startForeground()" crash ~5s later. Collapsing the window
            // emits only the settled state; distinctUntilChanged drops a no-op re-emit when a
            // brief true→false→true flurry settles back to the value already applied.
            anyRunActive
                .debounce(NetworkConfig.runForegroundDebounceMs)
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        // When exactly one run is active, resolve its title (and id, for the
                        // Stop action) so the notification identifies which session is running
                        // and can be aborted from the notification. Multiple concurrent runs
                        // fall back to the generic title (naming one would be misleading).
                        val activeId = synchronized(activeRuns) {
                            activeRuns.toList().singleOrNull()
                        }
                        val title = activeId?.let { sid ->
                            runCatchingCancellable {
                                activeConnection.value?.repository?.listSessions()
                                    ?.firstOrNull { it.id == sid }?.displayTitle
                            }.getOrNull()
                        }
                        val profileId = activeConnection.value?.profile?.id
                        RunForegroundService.start(ctx, title, activeId, profileId, _runProgressText.value)
                    } else {
                        RunForegroundService.stop(ctx)
                    }
                }
        }
        // Separate collector: update the FGS notification's progress text ("Step 2 of 5")
        // as the agent works through its task plan. Re-sends the service intent with the
        // updated progress, which the service uses to rebuild the notification.
        appScope.launch {
            _runProgressText
                .drop(1) // skip initial value — the anyRunActive collector handles the first start
                .distinctUntilChanged()
                .collect { progress ->
                    if (_anyRunActive.value) {
                        val activeId = synchronized(activeRuns) { activeRuns.toList().singleOrNull() }
                        val title = activeId?.let { sid ->
                            runCatchingCancellable {
                                activeConnection.value?.repository?.listSessions()
                                    ?.firstOrNull { it.id == sid }?.displayTitle
                            }.getOrNull()
                        }
                        val profileId = activeConnection.value?.profile?.id
                        RunForegroundService.start(ctx, title, activeId, profileId, progress)
                    }
                }
        }
    }

    /**
     * Post a system notification when the SSE stream hits a non-retryable failure
     * ([Failed]/[AuthFailed]) while the app is backgrounded during an active run, so a
     * backgrounded user is signaled that their run is stranded (the in-app banner isn't
     * visible). The notification is cancelled when the stream reconnects. This closes the
     * gap where a user who kicks off a run and backgrounds the app returns hours later to
     * find nothing happened and no signal why — the stream parked on a 4xx mid-run and the
     * in-app banner was never seen.
     */
    private fun observeBackgroundedConnectionFailures() {
        val ctx = appContext ?: return
        appScope.launch {
            activeConnection.collectLatest { conn ->
                if (conn == null) return@collectLatest
                conn.events.state.collect { state ->
                    when (state) {
                        EventStreamClient.ConnectionState.Failed,
                        EventStreamClient.ConnectionState.AuthFailed -> {
                            // Only notify when the app is backgrounded AND a run was recently
                            // active (or still is) — a foregrounded user sees the banner, and
                            // a failure with no run is less actionable.
                            if (!_isForeground.value && _anyRunActive.value) {
                                runCatchingCancellable {
                                    SessionNotifications.postConnectionLost(
                                        ctx, conn.profile.displayLabel, conn.profile.id,
                                    )
                                }.onFailure {
                                    Log.w("AppContainer", "connection-lost notification failed: ${safeExceptionSummary(it)}")
                                }
                            }
                        }
                        EventStreamClient.ConnectionState.Connected -> {
                            runCatchingCancellable { SessionNotifications.cancelConnectionLost(ctx) }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    /**
     * Reconcile active-run tracking on an SSE *reconnect*. A run that completes during a stream
     * outage never delivers its `SessionIdle`, so its id would stay in [activeRuns] and pin
     * [anyRunActive] (and the foreground service + "working…" notification) on indefinitely. On a
     * reconnect we clear the tracking and let live streaming events re-populate it: a genuinely
     * in-progress run re-adds itself within moments via [isLiveRunActivity], while a run that
     * finished during the outage produces no further live events (its completion arrives only via
     * the REST re-seed), so it correctly stays cleared. The first connect of a connection is not a
     * reconnect; `connect()` already resets [activeRuns], so only subsequent Connected transitions
     * trigger the sweep.
     */
    private fun observeRunReconcileOnReconnect() {
        appScope.launch {
            activeConnection.collectLatest { conn ->
                if (conn == null) return@collectLatest
                var hasConnectedBefore = false
                conn.events.state.collect { state ->
                    if (state == EventStreamClient.ConnectionState.Connected) {
                        if (hasConnectedBefore) {
                            synchronized(activeRuns) {
                                activeRuns.clear()
                                publishRunState()
                            }
                        }
                        hasConnectedBefore = true
                    }
                }
            }
        }
    }

    /**
     * Flush the offline outbox whenever there's an active connection and the device is
     * online (and on any manual [flushOutbox] nudge). Queued messages are only sent to the
     * server they were composed for (matched by profile id), so a reconnect to a different
     * server never misfires them against a session id that doesn't exist there. Uses a plain
     * `collect` (not collectLatest) so an in-flight send is never cancelled mid-flight and
     * send order is preserved; each message carries a stable idempotency key (its persistent
     * outbox id) so even a re-send across flushes is deduplicated server-side.
     */
    private fun observeOutbox() {
        appScope.launch {
            combine(activeConnection, isOnline, _outboxFlushTrigger) { conn, online, _ -> conn to online }
                .collect { (conn, online) ->
                    if (conn == null || !online) return@collect
                    flushOutboxNow(conn)
                }
        }
    }

    private suspend fun flushOutboxNow(conn: OpencodeConnection) {
        val pending = outboxStore.messages.value
            .filter { it.profileId == conn.profile.id }
            .sortedBy { it.createdAt }
        if (pending.isEmpty()) return
        _outboxSending.value = true
        try {
            for (msg in pending) {
                val result = runCatchingCancellable {
                    conn.repository.sendPrompt(
                        msg.sessionId,
                        msg.text,
                        attachments = msg.attachments.map {
                            FilePromptPart(mime = it.mime, url = it.url, filename = it.filename)
                        },
                        model = msg.model,
                        agent = msg.agent,
                        // Reuse the persistent outbox id as a stable idempotency key so a
                        // later flush of a message whose earlier POST reached the server (but
                        // whose response was lost) is deduplicated server-side instead of
                        // starting a duplicate agent run.
                        idempotencyKey = msg.id,
                    )
                }.onFailure {
                    Log.w("AppContainer", "Outbox flush failed for ${msg.sessionId}: ${safeExceptionSummary(it)}")
                }
                if (result.isSuccess) {
                    outboxStore.remove(msg.id)
                    continue
                }
                // A permanently-undeliverable message (a non-408/429 4xx — e.g. the target
                // session was deleted server-side, yielding a 404) must be dropped, not left at
                // the head of the queue. The loop stops at the first failure to preserve send
                // order, so a poison message would otherwise block every later queued message
                // for this profile forever. Transient failures (offline again, 5xx, 408/429)
                // keep the queue intact and retry on the next trigger.
                // 401/403 (bad/expired credentials) are NOT permanent: the message itself is
                // deliverable once the user fixes the profile. Dropping it would silently lose
                // the user's composed offline prompt on a recoverable auth failure. They stay
                // in the queue and retry on the next flush after auth is restored.
                val status = result.exceptionOrNull()?.let { responseStatusCode(it) }
                // Permanent = a non-408/429/401/403 4xx, matching withRetry's non-retryable rule
                // except for auth failures, which are recoverable by re-editing the profile.
                val permanent = when (status) {
                    null, 408, 429, 401, 403 -> false
                    in 400..499 -> true
                    else -> false
                }
                if (permanent) {
                    Log.w(
                        "AppContainer",
                        "Dropping undeliverable outbox message ${msg.id} for ${msg.sessionId} (HTTP $status): ${string(R.string.outbox_dropped_text)}",
                    )
                    outboxStore.remove(msg.id)
                    // Surface the dropped reply on the ERROR channel with a distinct title
                    // ("Message not delivered") so the user understands a queued reply
                    // couldn't be delivered — not that a run failed (which postError
                    // implies). Tap routes back to the originating profile's session.
                    val ctx = appContext
                    if (ctx != null) {
                        val title = resolveSessionTitle(msg.sessionId, conn.repository)
                        runCatchingCancellable {
                            SessionNotifications.postOutboxDropped(
                                ctx, msg.sessionId,
                                title,
                                conn.profile.id,
                            )
                        }.onFailure {
                            Log.w("AppContainer", "outbox drop notification failed: ${safeExceptionSummary(it)}")
                        }
                    }
                } else {
                    break
                }
            }
        } finally {
            _outboxSending.value = false
        }
    }

    /** Release resources held for the process lifetime (network callback, app scope). */
    open fun shutdown() {
        // Cancel the app scope first so any coroutine holding the connection mutex
        // (e.g. a connect() suspended on profileStore) is cancelled and releases the
        // mutex. Otherwise the runBlocking below would deadlock waiting for a coroutine
        // that can't be cancelled until after the runBlocking completes.
        appScope.cancel()
        // Capture the active connection BEFORE the bounded close attempt so a timeout
        // can still close it outside the mutex. Without this, the timeout path would
        // null the StateFlow reference without closing the connection — leaking the SSE
        // socket, OkHttp pool, and the connection's coroutine scope.
        val stuck = _activeConnection.value
        var closed = false
        // Bounded runBlocking so a stuck mutex or a slow close() can't ANR the app.
        // 2 seconds is generous: appScope.cancel() already triggered cancellation of
        // any coroutine holding the mutex; we're just waiting for it to unwind.
        kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(2_000) {
                connectionMutex.withLock {
                    runCatching { _activeConnection.value?.close() }
                    _activeConnection.value = null
                    closed = true
                }
            }
            // If the timeout fired, force-clear the StateFlow so observers see no
            // connection. The stuck connection (if any) is closed below, outside the
            // mutex, so a slow close() can't ANR — its scope is cancelled and the JVM
            // exit reaps any remaining sockets.
            _activeConnection.value = null
        }
        if (!closed && stuck != null) {
            // The timeout path fired (the locked close didn't complete). Cancel the
            // stuck connection's scope directly. close() suspends (scopeJob.join), so
            // wrap in a bounded runBlocking that can't ANR — at this point the app is
            // shutting down, so reaping is best-effort.
            runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(1_000) { stuck.close() }
                }
            }
        }
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        if (!skipInit) soy.iko.opencode.data.repo.CrashLogger.get(appContext!!).shutdown()
        if (!skipInit) draftStore.shutdown()
    }

    /**
     * Watch the SSE bus for new message activity and badge any session that isn't
     * currently open. This powers the unread dot on the session list so the user can
     * tell which conversations got a reply while they were elsewhere.
     *
     * Uses [flatMapLatest] so a server switch (which replaces the active connection)
     * re-subscribes to the new event stream and drops the old one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeMessageActivity() {
        appScope.launch {
            // Retry the observer if it fails for any reason (transient flow exception,
            // cancellation due to scope issues). Without this, a single failure would
            // permanently disable unread tracking and completion notifications.
            while (isActive) {
                runCatchingCancellable {
                    activeConnection
                        .flatMapLatest { conn ->
                            // Capture the originating connection so notification coroutines
                            // resolve titles and route permission responses against THIS
                            // server, not whichever happens to be active when they resume.
                            // A server switch during a listSessions() round-trip would
                            // otherwise resolve the title (or POST the permission reply)
                            // against the new server, where the session/permission doesn't
                            // exist — showing an opaque id or silently losing the reply.
                            conn?.events?.events?.map { conn to it } ?: emptyFlow()
                        }
                        .collect { (conn, event) ->
                    // A permission request needs the user's approval. Post a heads-up
                    // notification (with Allow/Reject actions) unless the user is actively
                    // viewing this session, where the in-app permission dialog handles it.
                    if (event is PermissionUpdated) {
                        val psid = event.properties.sessionID.takeIf { it.isNotBlank() } ?: return@collect
                        if (!isActivelyViewing(psid)) appScope.launch { notifyPermission(event.properties, conn) }
                        return@collect
                    }
                    // The permission was answered (in-app, from the notification, or
                    // auto-resolved): clear its heads-up notification.
                    if (event is PermissionReplied) {
                        val psid = event.properties.sessionID?.takeIf { it.isNotBlank() } ?: return@collect
                        appContext?.let { SessionNotifications.cancelPermission(it, psid) }
                        return@collect
                    }
                    // A run failed. Clear its active-run tracking (an error isn't always
                    // followed by a SessionIdle) and notify unless it's being viewed.
                    if (event is SessionError) {
                        val esid = event.properties.sessionID ?: return@collect
                        val wasRunning = synchronized(activeRuns) {
                            activeRuns.remove(esid).also { removed ->
                                if (removed) publishRunState()
                            }
                        }
                        if (wasRunning && !isActivelyViewing(esid)) {
                            appScope.launch { notifySessionError(esid, conn.repository, conn.profile.id) }
                        }
                        return@collect
                    }
                    // SessionIdle is not "message activity" (don't badge as unread)
                    // but signals a run finished — fire a completion notification if the
                    // session isn't currently being viewed and was actively streaming.
                    if (event is SessionIdle) {
                        val idleSid = event.properties.sessionID ?: return@collect
                        // Clear the run state unconditionally — even for the session on
                        // screen. Gating the removal on `idleSid != currentSession` (as
                        // this once did) leaks the id in activeRuns and pins anyRunActive
                        // true forever whenever a run finishes while being viewed. Only
                        // the completion *notification* is suppressed for the viewed one.
                        val wasRunning = synchronized(activeRuns) {
                            activeRuns.remove(idleSid).also { removed ->
                                if (removed) publishRunState()
                            }
                        }
                        // Clear the progress text when the run finishes.
                        _runProgressText.value = null
                        if (wasRunning && !isActivelyViewing(idleSid)) {
                            // Fire this off the collector's coroutine: notifySessionCompleted
                            // does a network listSessions() to resolve the title, and blocking
                            // the shared-event collector here would stall the SharedFlow,
                            // overflowing its DROP_OLDEST buffer and silently dropping live
                            // parts/updates for every other subscriber (e.g. the message reducer).
                            appScope.launch { notifySessionCompleted(idleSid, conn.repository, conn.profile.id) }
                        }
                        return@collect
                    }
                    // Track the agent's task-plan progress (from todowrite tool calls) so the
                    // foreground-service notification can show "Step 2 of 5" instead of just a
                    // chronometer. The todowrite tool sends the full plan on every call, so the
                    // latest call's input is a complete snapshot.
                    if (event is MessagePartUpdated) {
                        val part = event.properties.part
                        if (part is ToolPart && part.tool.equals(TODO_WRITE_TOOL, ignoreCase = true)) {
                            val todos = parseTodos(part.state.inputElement())
                            if (todos.isNotEmpty()) {
                                val completed = todos.count { it.statusEnum() == TodoStatus.COMPLETED }
                                _runProgressText.value = appContext?.getString(
                                    soy.iko.opencode.R.string.notif_running_progress,
                                    completed + 1,
                                    todos.size,
                                )
                            }
                        }
                    }
                    val sid = sessionOf(event) ?: return@collect
                    // Re-read currentSession here, right before mutating the badge state:
                    // the user may have opened this session between when this event was
                    // enqueued and now (setCurrentSession clears its badge and dedup set).
                    // A stale earlier check would race a badge — and a dedup entry — back
                    // in for the session that's actually on screen.
                    if (sid != _currentSession.value) {
                        // Track the message id in the dedup set for *every* session (muted or
                        // not), so unmuting can't re-count messages that arrived while muted.
                        // The visible badge increment below is gated on !muted so a muted
                        // conversation doesn't interrupt, but the dedup tracking must not be
                        // — otherwise the first event after unmute for an already-seen message
                        // id would pass the counted.add() check (empty set) and re-increment.
                        val messageId = messageIdOfEvent(event)
                        val counted = unreadMessageIds.getOrPut(sid) {
                            java.util.Collections.synchronizedSet(mutableSetOf())
                        }
                        val isNewMessage = messageId == null || counted.add(messageId)
                        if (isNewMessage && sid !in _mutedSessions.value) {
                            // Skip the unread badge for muted sessions — the count is still
                            // tracked in unreadMessageIds (so unmuting can't re-count the
                            // same messages), but the visible badge is suppressed so a muted
                            // conversation doesn't interrupt. The completion notification is
                            // also gated on isSessionMuted (see notifySessionCompleted).
                            _unread.update { current ->
                                // Guard again inside the atomic update lambda so a retry
                                // (or an open that landed just now) can't reintroduce the
                                // badge for the session on screen.
                                if (sid == _currentSession.value) current
                                else current + (sid to (current[sid] ?: 0) + 1)
                            }
                        }
                    }
                    // Track sessions actively streaming so we know which idle events
                    // represent a finished run worth notifying about.
                    if (isLiveRunActivity(event)) {
                        // add() returns true only when the id is genuinely new. Only republish
                        // the run-state snapshot in that case: a continuously-streaming session
                        // re-adds its id on every token (hundreds/sec), and republishing each
                        // time allocates a fresh Set via toSet() + writes two StateFlows under
                        // the monitor — pure overhead since the set membership didn't change.
                        // The access-order refresh from LinkedHashSet.add still happens (keeping
                        // the session from being evicted by a reconnect sweep); we just skip the
                        // O(set size) snapshot + StateFlow writes when nothing changed.
                        synchronized(activeRuns) {
                            if (activeRuns.add(sid)) {
                                publishRunState()
                            }
                        }
                    }
                } }.onFailure { Log.w("AppContainer", "Message activity observer failed, will retry: ${safeExceptionSummary(it)}") }
                if (!isActive) break
                delay(NetworkConfig.observerRetryDelayMs)
            }
        }
    }

    /** True if [event] is live streaming activity worth tracking as an active run. A
     *  trailing message.updated that lands *after* the run finished (final cost/token totals,
     *  carrying a completion time) is NOT live streaming: with no further SessionIdle to
     *  follow it, re-adding the session would pin anyRunActive true indefinitely. */
    private fun isLiveRunActivity(event: BusEvent): Boolean {
        if (event !is MessagePartUpdated && event !is MessageUpdated) return false
        // For MessageUpdated, only an in-flight *assistant* message counts as live activity.
        // A UserMessage or UnknownMessage update (e.g. a metadata refresh of an existing user
        // message) would otherwise add the session to activeRuns with no following SessionIdle
        // to clear it — pinning anyRunActive true and the foreground notification indefinitely.
        // Mirrors SessionRepository.isRunActivity's guard exactly.
        if (event is MessageUpdated) {
            val info = event.properties.info
            return info is AssistantMessage && !info.isComplete && info.error == null
        }
        // A step-finish part is the trailing completion marker for a run (it carries the
        // final cost/token totals) and arrives after the last stream delta. Like a completed
        // message.updated, treating it as live would re-add the session to activeRuns with no
        // following SessionIdle to clear it — pinning anyRunActive true indefinitely when such
        // a part is replayed/reordered after the run already went idle.
        val part = (event as MessagePartUpdated).properties.part
        return part !is StepFinishPart
    }

    /** Snapshot [activeRuns] into [_runningSessionIds] and derive [_anyRunActive] from it.
     *  MUST be called under the [activeRuns] monitor so the published flag and snapshot can't
     *  diverge from the set a concurrent SSE add()/remove() is mutating — the same invariant
     *  [connectLocked]/[teardownActiveLocked] rely on. */
    private fun publishRunState() {
        _anyRunActive.value = activeRuns.isNotEmpty()
        _runningSessionIds.value = activeRuns.toSet()
    }

    /** Session ids currently streaming an assistant run (best-effort, in-process). Backed by
     *  an access-order [java.util.LinkedHashMap] so re-adding an already-present id on each
     *  streaming event refreshes its recency (a plain LinkedHashSet.add is a no-op that does
     *  NOT reorder). Entries are drained by [SessionIdle] (and cleared wholesale on reconnect
     *  reconcile); we intentionally do NOT cap the set, because evicting the eldest would drop
     *  its completion notification when its SessionIdle eventually arrives — a busy server
     *  with many concurrent runs is still bounded by the server's own concurrency, so growth
     *  is not unbounded in practice. */
    private val activeRuns: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.LinkedHashMap<String, Boolean>(16, 0.75f, true)),
    )

    /** Per non-viewed session, the set of message ids already reflected in its unread
     *  count. De-duplicates the many streaming events that share one message id so the
     *  badge counts messages, not deltas. Cleared when a session is viewed or on connect. */
    private val unreadMessageIds = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** Guards connect/disconnect so concurrent callers can't leak an old connection. */
    private val connectionMutex = Mutex()

    /** Only persist the `lastUsed` timestamp if it's older than this, to avoid
     *  a DataStore + encrypted-prefs write on every rapid reconnect. */
    private companion object {
        const val LAST_USED_SAVE_THRESHOLD_MS = 60_000L
    }

    /** Resolve the human-readable title for [sessionId] (best-effort), falling back to the id.
     *  Uses [originRepo] — the repository of the connection that emitted the event — so a
     *  server switch during the listSessions() round-trip doesn't resolve the title against
     *  an unrelated server (showing an opaque id, or worse, a colliding session's title). */
    private suspend fun resolveSessionTitle(sessionId: String, originRepo: SessionRepository): String =
        runCatchingCancellable {
            originRepo.listSessions()?.firstOrNull { it.id == sessionId }?.displayTitle
        }.getOrNull() ?: sessionId

    /** Resolve the title for [sessionId] (best-effort) and post a completion notification.
     *  [originProfileId] is the id of the server that ran the session; it's embedded in the
     *  inline-reply action so a follow-up routes back to THIS server even after the user switches
     *  connections (mirrors [notifyPermission]). */
    private suspend fun notifySessionCompleted(sessionId: String, originRepo: SessionRepository, originProfileId: String) {
        val title = resolveSessionTitle(sessionId, originRepo)
        // Re-check viewing right before posting: the call site checks !isActivelyViewing(sid)
        // at event time, but resolveSessionTitle() suspends on a listSessions() round-trip.
        // If the user opened the session in that gap, setCurrentSession already cancelled the
        // prior notification — posting a fresh one here would leave a "session ready" heads-up
        // lingering while they're already looking at the finished run.
        if (isActivelyViewing(sessionId)) return
        // Suppress the completion notification for muted sessions — the whole point of muting
        // is that a noisy conversation doesn't interrupt.
        if (sessionId in _mutedSessions.value) return
        appContext?.let { SessionNotifications.postCompleted(it, sessionId, title, originProfileId) }
    }

    /** Post a heads-up permission notification for [permission], resolving its session title.
     *  [originConn] is the connection that emitted the permission event — its profile id is
     *  embedded in the notification so the receiver routes the response back to THIS server. */
    private suspend fun notifyPermission(permission: Permission, originConn: OpencodeConnection) {
        val sessionId = permission.sessionID.takeIf { it.isNotBlank() } ?: return
        val title = resolveSessionTitle(sessionId, originConn.repository)
        // Re-check viewing just before posting — see notifySessionCompleted for the race.
        if (isActivelyViewing(sessionId)) return
        appContext?.let { SessionNotifications.postPermission(it, permission, title, originConn.profile.id) }
    }

    /** Post an error notification for a failed background run. [originProfileId] is embedded
     *  in the tap intent so the session opens under the server that ran it, not whichever
     *  is active when the user taps — mirroring [notifySessionCompleted]. */
    private suspend fun notifySessionError(sessionId: String, originRepo: SessionRepository, originProfileId: String) {
        val title = resolveSessionTitle(sessionId, originRepo)
        // Re-check viewing just before posting — see notifySessionCompleted for the race.
        if (isActivelyViewing(sessionId)) return
        appContext?.let { SessionNotifications.postError(it, sessionId, title, originProfileId) }
    }

    /** Respond to a permission from a notification action (Allow once / Always / Reject),
     *  off any UI. Runs on the process-lived app scope; [onDone] fires when the call resolves
     *  so the receiver's goAsync() result can finish. Its Boolean is true only when a live
     *  connection existed AND respondPermission succeeded — the receiver uses it to decide
     *  whether to dismiss the notification. A missing connection makes the respond a no-op,
     *  so it must report failure (not success) rather than let the notification vanish with
     *  the tool left unanswered.
     *
     *  [profileId] is the id of the server that posted the permission request (embedded in
     *  the notification's PendingIntent). If it doesn't match the currently active
     *  connection's profile, the response is NOT sent: posting it to a different server
     *  would 404 (treated as "already resolved" success by respondPermission), dismissing
     *  the notification while the original server's tool stays paused forever. Reporting
     *  failure instead leaves the notification up so the user can switch back and retry. */
    open fun respondToPermissionFromNotification(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        profileId: String?,
        onDone: (Boolean) -> Unit,
    ) {
        appScope.launch {
            // Guarantee onDone (so the BroadcastReceiver's goAsync() finishes) even if the
            // scope is cancelled mid-call (process shutdown cancels appScope first). Without
            // this, a cancelled scope leaves pending.finish() never called → a receiver ANR
            // and the permission response silently lost.
            var success = false
            try {
                success = runCatchingCancellable {
                    val conn = activeConnection.value ?: return@runCatchingCancellable false
                    // Route to the originating server only. A mismatch means the user
                    // switched servers while the permission was pending; sending the reply
                    // to the wrong server would be silently swallowed as a 404-success.
                    if (profileId != null && conn.profile.id != profileId) return@runCatchingCancellable false
                    conn.api.respondPermission(sessionId, permissionId, response)
                    true
                }.onFailure {
                    Log.w("AppContainer", "notif permission respond failed: ${safeExceptionSummary(it)}")
                }.getOrDefault(false)
            } finally {
                onDone(success)
            }
        }
    }

    /** Send a follow-up prompt from a notification's inline reply, off any UI. Uses the
     *  server's default model/agent. Routes through the durable [outboxStore] — the same path
     *  the in-app composer uses ([soy.iko.opencode.ui.chat.ChatViewModel.enqueueOffline]) —
     *  rather than a direct repository send, so a reply composed while disconnected survives to
     *  be flushed on reconnect instead of being silently dropped. [flushOutbox] nudges an
     *  immediate send when a connection is live, so an online reply still goes out now. [onDone]
     *  reports whether the reply was durably enqueued so the receiver only dismisses the
     *  notification on success. */
    open fun sendPromptFromNotification(sessionId: String, text: String, originProfileId: String?, onDone: (Boolean) -> Unit) {
        appScope.launch {
            // Guarantee onDone (so the BroadcastReceiver's goAsync() finishes) even if the
            // scope is cancelled mid-call — see respondToPermissionFromNotification above.
            var enqueued = false
            try {
                // Attribute to the profile that POSTED the notification (embedded in the reply
                // intent) so the follow-up is always enqueued against the server that owns
                // [sessionId] — never whichever server happens to be active now, which would POST
                // A's session id to B, 404, and silently drop the reply. Fall back to the active/
                // most-recent profile only for a legacy intent that predates the embedded id.
                val profileId = originProfileId
                    ?: activeConnection.value?.profile?.id
                    ?: runCatchingCancellable { profileStore.profiles.first().firstOrNull()?.id }.getOrNull()
                enqueued = if (profileId == null) {
                    false
                } else {
                    runCatchingCancellable {
                        outboxStore.enqueue(
                            OutboxMessage(
                                id = java.util.UUID.randomUUID().toString(),
                                profileId = profileId,
                                sessionId = sessionId,
                                text = text,
                                model = null,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }.onFailure {
                        Log.w("AppContainer", "notif reply enqueue failed: ${safeExceptionSummary(it)}")
                    }.isSuccess
                }
                if (enqueued) flushOutbox()
            }             finally {
                onDone(enqueued)
            }
        }
    }

    /** Abort the active run for [sessionId] from a notification Stop action. Routes to the
     *  originating profile only — sending the abort to a different server would 404 (silently
     *  swallowed) and leave the original run stranded. A profile mismatch reports failure so the
     *  notification stays up and the user can switch back, mirroring
     *  [respondToPermissionFromNotification]'s routing guard. Runs on the app scope so it
     *  survives the BroadcastReceiver's lifetime. */
    open fun abortRunFromNotification(sessionId: String, originProfileId: String?, onDone: (Boolean) -> Unit) {
        appScope.launch {
            var success = false
            try {
                val conn = activeConnection.value?.takeIf {
                    originProfileId == null || it.profile.id == originProfileId
                }
                if (conn != null) {
                    success = runCatchingCancellable { conn.repository.abort(sessionId) }.isSuccess
                }
            } finally {
                onDone(success)
            }
        }
    }

    /**
     * Retry the last user prompt for [sessionId] from an error notification's "Retry last"
     * action. Fetches the session's messages, re-sends the most recent user prompt, and
     * cancels the error notification on success. Routes to the originating profile's
     * connection only — sending the retry to a different server would 404 or start a fresh
     * run on an unrelated session id. A profile mismatch reports failure so the notification
     * stays up and the user can switch back, mirroring [respondToPermissionFromNotification]'s
     * routing guard. A no-op (reported as false) when there's no connection or no prior prompt.
     */
    open fun retryLastFromNotification(sessionId: String, originProfileId: String?, onDone: (Boolean) -> Unit) {
        appScope.launch {
            var success = false
            try {
                val conn = activeConnection.value?.takeIf {
                    originProfileId == null || it.profile.id == originProfileId
                }
                if (conn != null) {
                    success = runCatchingCancellable {
                        val messages = conn.api.listMessages(sessionId)
                        val lastPrompt = messages
                            .lastOrNull { it.info is soy.iko.opencode.data.model.UserMessage }
                            ?.parts
                            ?.filterIsInstance<soy.iko.opencode.data.model.TextPart>()
                            ?.joinToString("\n\n") { it.text }
                            ?.takeIf { it.isNotBlank() }
                        if (lastPrompt != null) {
                            // model=null lets the server reuse the session's current model; we
                            // don't know the originally-selected model from a notification context.
                            conn.repository.sendPrompt(sessionId, lastPrompt, model = null)
                            true
                        } else {
                            false
                        }
                    }.isSuccess
                }
            } finally {
                onDone(success)
            }
        }
    }

    /** Extract the session id an event pertains to, for message-activity events. */
    private fun sessionOf(event: BusEvent): String? = sessionOfEvent(event)

    /**
     * On cold start, transparently reconnect to the most recently used server so a
     * returning user lands in their session list instead of the empty server screen.
     */
    private suspend fun autoConnect() {
        val recent = runCatchingCancellable { profileStore.profiles.first() }
            .getOrDefault(emptyList())
            .firstOrNull() ?: return
        // The profile read above suspends on DataStore. A manual connect() to a different
        // server during that window establishes a connection the user explicitly chose; we must
        // not clobber it by connecting to `recent` on top. connectIfIdle() checks "is anything
        // active?" and connects atomically under connectionMutex, returning null if the cold-start
        // slot was already taken — closing the TOCTOU window an unlocked null-check plus connect()'s
        // unconditional close would leave. (disconnectIf below similarly guards the failed-ping
        // teardown by identity.)
        _reconnecting.value = true
        var conn: OpencodeConnection? = null
        try {
            val ok = runCatchingCancellable {
                conn = connectIfIdle(recent) ?: return@runCatchingCancellable false
                conn!!.api.ping()
                true
            }.getOrDefault(false)
            when {
                conn == null -> Unit // slot already taken by a manual connect — leave it be
                ok -> _autoConnectDone.value = true
                else -> disconnectIf(conn!!)
            }
        } finally {
            // Reset in a finally so a CancellationException (e.g. scope cancelled during
            // shutdown) can't leave the flag pinned true — runCatchingCancellable rethrows it,
            // skipping the line below the try block.
            _reconnecting.value = false
        }
    }

    /**
     * When the device regains connectivity, nudge the active SSE stream to reconnect
     * immediately instead of waiting out the exponential backoff.
     */
    private val networkCallback: ConnectivityManager.NetworkCallback? = registerNetworkMonitor()

    private fun registerNetworkMonitor(): ConnectivityManager.NetworkCallback? {
        if (appContext == null) return null
        val cm = appContext!!.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        // Seed the online state from the current active network so the indicator is
        // correct on cold start rather than defaulting to "online" until a callback fires.
        _isOnline.value = runCatching { cm.activeNetwork != null }.getOrDefault(true)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _isOnline.value = true; activeConnection.value?.events?.triggerReconnect() }
            // A matched (internet-capable) network was lost. Don't eagerly flip offline: during a
            // Wi-Fi→cellular handoff onLost(WiFi) fires before onAvailable(cellular) and
            // cm.activeNetwork is transiently null in that window — flashing offline there would
            // skip outbox flushing and blink the indicator. Instead re-check activeNetwork after a
            // short grace period and only report offline if there's still no active network.
            //
            // This MUST live in onLost, not onUnavailable: onUnavailable is delivered only for
            // requestNetwork() with a timeout, never for registerNetworkCallback() — relying on it
            // (as this once did) meant offline was never detected after the first onAvailable.
            override fun onLost(network: Network) {
                appScope.launch {
                    delay(NetworkConfig.networkOfflineGraceMs)
                    _isOnline.value = runCatching { cm.activeNetwork != null }.getOrDefault(true)
                }
            }
        }
        // Only match networks that actually provide internet — a capability-less request
        // fires onAvailable for transports that can't reach the server (and would trigger
        // spurious reconnects), so require NET_CAPABILITY_INTERNET.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.w("AppContainer", "Failed to register network callback", it) }
        return callback
    }

    /** Open (or re-open) a connection to [profile], replacing any current one.
     *  Only persists the updated `lastUsed` timestamp if it's stale (older than a
     *  minute), so rapid reconnect storms don't each trigger a DataStore write. */
    open suspend fun connect(profile: ServerProfile): OpencodeConnection =
        connectionMutex.withLock { connectLocked(profile) }

    /** Connect to the profile with [profileId], looking it up from [profileStore]. Returns
     *  true on success, false if the profile wasn't found or connect threw. Used by the
     *  notification body-tap path to route a session open back to the originating server. */
    open suspend fun connectByProfileId(profileId: String): Boolean {
        val profile = profileStore.profiles.first().find { it.id == profileId } ?: return false
        return runCatchingCancellable { connect(profile) }.isSuccess
    }

    /** Connect to [profile] only if nothing is currently active, checking-and-connecting
     *  atomically under [connectionMutex]. Returns null (touching nothing) when a connection is
     *  already active, so autoConnect() can't clobber a manual connect() the user made during its
     *  suspending profile read — closing the TOCTOU window an unlocked null-check would leave. */
    private suspend fun connectIfIdle(profile: ServerProfile): OpencodeConnection? =
        connectionMutex.withLock {
            if (_activeConnection.value != null) null else connectLocked(profile)
        }

    /** Build and install a connection to [profile], replacing any current one. Caller MUST hold
     *  [connectionMutex]. */
    private suspend fun connectLocked(profile: ServerProfile): OpencodeConnection {
        // Capture the previous profile id before closing, so we can preserve unread badges
        // when reconnecting to the same server — a brief network blip or manual reconnect
        // shouldn't erase "5 unread". Only a genuine server switch clears them.
        val previousProfileId = _activeConnection.value?.profile?.id
        _activeConnection.value?.close()
        _activeConnection.value = null
        // Mutate the run set and its derived flag together under activeRuns' monitor: the SSE
        // event handler adds ids under the same lock, so an unlocked clear() here could
        // interleave with an add()+flag=true and leave _anyRunActive pinned true with a torn-
        // down connection and no SessionIdle to clear it — a stuck foreground service.
        synchronized(activeRuns) {
            activeRuns.clear()
            publishRunState()
        }
        if (previousProfileId != profile.id) {
            _unread.value = emptyMap()
            unreadMessageIds.clear()
        }
        val now = System.currentTimeMillis()
        val resolved = profileStore.resolve(profile)
        val needsSave = (now - resolved.lastUsed) > LAST_USED_SAVE_THRESHOLD_MS
        val finalProfile = if (needsSave) resolved.copy(lastUsed = now) else resolved
        if (needsSave) profileStore.save(finalProfile)
        return OpencodeConnection(finalProfile, messageCacheStore).also { _activeConnection.value = it }
    }

    open suspend fun disconnect() {
        // Acquire the connection mutex so disconnect is serialized with connect().
        // Previously this was fire-and-forget (launch on appScope), which raced with
        // a subsequent connect(): connect() could acquire the mutex, create a new
        // connection, and then the pending disconnect() would close it.
        connectionMutex.withLock { teardownActiveLocked() }
    }

    /** Tear down [expected] only if it is still the active connection, with the identity
     *  check performed *under* the mutex. A caller that checks `activeConnection.value === x`
     *  itself and then calls [disconnect] has a TOCTOU race: a concurrent connect() to a
     *  different server can replace the active connection in the gap, and [disconnect] would
     *  then close that new connection. autoConnect()'s failed-ping teardown uses this. */
    private suspend fun disconnectIf(expected: OpencodeConnection) {
        connectionMutex.withLock {
            if (_activeConnection.value === expected) teardownActiveLocked()
        }
    }

    /** Close and reset the active connection. Caller MUST hold [connectionMutex]. */
    private suspend fun teardownActiveLocked() {
        _activeConnection.value?.close()
        _activeConnection.value = null
        // Reset run/unread state on disconnect too. With no SSE stream there's no
        // SessionIdle to drain activeRuns, so without this the working indicator
        // (anyRunActive) and unread badges would stay pinned until the next connect().
        // Mutate under activeRuns' monitor (see connect()) so a concurrent SSE add() can't
        // re-pin _anyRunActive after the clear.
        synchronized(activeRuns) {
            activeRuns.clear()
            publishRunState()
        }
        _unread.value = emptyMap()
        unreadMessageIds.clear()
    }

    /** Deferred profile deletions keyed by id, run on the process-lived [appScope] (not a
     *  ViewModel scope) so navigating away during the undo window still commits the delete
     *  instead of silently cancelling it — mirroring [CrashLogger.scheduleDelete]. */
    private val pendingProfileDeletes = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * Schedule the profile [id] for deletion after [delayMs], cancellable via
     * [cancelProfileDelete] (the Undo action). [onDeleted]/[onError] run on [appScope]
     * after the delete resolves so the caller can clear its optimistic-hide state. A
     * repeated schedule for the same id replaces the prior timer.
     */
    open fun scheduleProfileDelete(
        id: String,
        delayMs: Long,
        onDeleted: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val job = appScope.launch {
            delay(delayMs)
            // Claim the delete by removing our own entry BEFORE committing it — the atomic
            // remove is the sync point with cancelProfileDelete. Losing the race (an undo
            // removed us first) means we skip, honouring cancel's `true`; deleting first
            // would let a cancel land in the gap and falsely report undo of a gone profile.
            // remove(id, thisJob) also no-ops for a stale job after a reschedule.
            val claimed = coroutineContext[Job]?.let { pendingProfileDeletes.remove(id, it) } == true
            if (!claimed) return@launch
            runCatchingCancellable { profileStore.delete(id) }
                .onSuccess { onDeleted() }
                .onFailure { onError(it) }
        }
        pendingProfileDeletes[id]?.cancel()
        pendingProfileDeletes[id] = job
    }

    /** Cancel a pending deferred profile delete (the Undo action). Returns true if it was
     *  still pending (undo succeeded), false if the delete had already fired. */
    open fun cancelProfileDelete(id: String): Boolean {
        val job = pendingProfileDeletes.remove(id) ?: return false
        job.cancel()
        return true
    }

    /** Deferred session deletions keyed by id, run on the process-lived [appScope] (not a
     *  ViewModel scope) so navigating away during the undo window still commits the delete
     *  instead of silently cancelling it (which would leave the session deleted-in-UI but
     *  alive on the server, reappearing on the next refresh). */
    private val pendingSessionDeletes = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** One-shot events carrying the id of a session scheduled for deferred deletion from
     *  outside the session list (e.g. from the chat screen's Delete). The SessionListScreen
     *  collects these to show an Undo snackbar, mirroring its own VM-level undo events. */
    private val _externalSessionUndoEvents = MutableSharedFlow<String>(
        extraBufferCapacity = NetworkConfig.snackbarEventBufferCapacity,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val externalSessionUndoEvents: SharedFlow<String> = _externalSessionUndoEvents.asSharedFlow()

    /** Emit an undo event for a session delete initiated outside the session list (e.g.
     *  from the chat screen). The SessionListScreen collects these to show an Undo snackbar. */
    fun emitExternalSessionUndo(sessionId: String) {
        _externalSessionUndoEvents.tryEmit(sessionId)
    }

    /**
     * Schedule session [id] for deletion after [delayMs], cancellable via [cancelSessionDelete]
     * (the Undo action). Also clears the session's stored draft on a successful delete.
     * [onDeleted]/[onError] run on [appScope] after the delete resolves so the caller can update
     * its optimistic-hide state. A repeated schedule for the same id replaces the prior timer.
     *
     * The owning connection is captured now, at schedule time — NOT re-resolved when the timer
     * fires. Otherwise a server switch during the undo window would send the delete to whichever
     * server happens to be active at fire time: the original session would never be deleted (its
     * row reappears on the next refresh) and, in the rare id-collision case, an unrelated session
     * on the new server could be deleted instead. A disconnect closes the captured connection's
     * HTTP client, so a delete deferred across a disconnect fails and is surfaced via [onError]
     * (the session cannot be deleted on a server we're no longer connected to). No-ops (as an
     * error) if there is no active connection at schedule time.
     */
    open fun scheduleSessionDelete(
        id: String,
        delayMs: Long,
        onDeleted: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val conn = _activeConnection.value
        val job = appScope.launch {
            delay(delayMs)
            // Claim the delete by removing our own entry BEFORE committing it — the atomic
            // remove is the sync point with cancelSessionDelete. Losing the race (an undo
            // removed us first) means we skip, honouring cancel's `true`; deleting first
            // would let a cancel land in the gap and falsely report undo of a gone session.
            // remove(id, thisJob) also no-ops for a stale job after a reschedule.
            val claimed = coroutineContext[Job]?.let { pendingSessionDeletes.remove(id, it) } == true
            if (!claimed) return@launch
            if (conn == null) {
                onError(IllegalStateException("No active connection"))
            } else {
                runCatchingCancellable { conn.repository.deleteSession(id) }
                    .onSuccess {
                        draftStore.remove(id)
                        attachmentDraftStore.remove(id)
                        sessionPrefsStore.forget(id)
                        messageCacheStore.remove(conn.profile.id, id)
                        outboxStore.removeForSession(id)
                        clearUnread(id)
                        onDeleted()
                    }
                    .onFailure { onError(it) }
            }
        }
        pendingSessionDeletes[id]?.cancel()
        pendingSessionDeletes[id] = job
    }

    /** Cancel a pending deferred session delete (the Undo action). Returns true if it was
     *  still pending (undo succeeded), false if the delete had already fired. */
    open fun cancelSessionDelete(id: String): Boolean {
        val job = pendingSessionDeletes.remove(id) ?: return false
        job.cancel()
        return true
    }

    /**
     * Probe a server URL (without credentials) to check reachability and detect whether
     * the server requires authentication. Builds a short-lived HTTP client with the same
     * base URL normalization as [connect] but no auth, calls the health endpoint, and
     * classifies the outcome:
     *
     * - 2xx → [ProbeResult.Reachable] (no auth needed)
     * - 401/403 → [ProbeResult.NeedsAuth]
     * - anything else → [ProbeResult.Unreachable] with a user-facing message
     *
     * The probe client is always closed afterwards so no resources linger. This does not
     * touch the active connection or the profile store.
     */
    open suspend fun probeServer(baseUrl: String, requireHttps: Boolean, certPin: String?): ProbeResult {
        return probeWithProfile(ServerProfile(
            id = "probe",
            label = "",
            baseUrl = baseUrl.trim(),
            username = null,
            password = null,
            // Probe the same effective URL save()/connect() will use: HttpClientFactory upgrades
            // http->https and installs the cert pinner when these are set, so a probe without them
            // would validate a plain-http endpoint the real connection never talks to.
            requireHttps = requireHttps,
            certPin = certPin,
        ))
    }

    /**
     * Probe a server URL *with* credentials to validate them. Returns true if the
     * server accepts the credentials (ping succeeds), false on 401/403, and throws
     * on other failures (unreachable, timeout) so the caller can surface a friendly
     * message. Mirrors [probeServer]'s short-lived-client pattern.
     */
    open suspend fun probeWithCredentials(
        baseUrl: String,
        username: String,
        password: String,
        requireHttps: Boolean,
        certPin: String?,
    ): Boolean {
        val probeProfile = ServerProfile(
            id = "probe-auth",
            label = "",
            baseUrl = baseUrl.trim(),
            username = username.trim().takeIf { it.isNotBlank() },
            password = password.trim().takeIf { it.isNotEmpty() },
            // Match the effective URL/pinning save()/connect() will use (see probeServer).
            requireHttps = requireHttps,
            certPin = certPin,
        )
        val client = HttpClientFactory.create(probeProfile)
        return try {
            val api = OpencodeApiClient(client)
            api.ping()
            true
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            val status = responseStatusCode(e)
            if (status == 401 || status == 403) false else throw e
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun probeWithProfile(profile: ServerProfile): ProbeResult {
        val client = HttpClientFactory.create(profile)
        return try {
            val api = OpencodeApiClient(client)
            api.ping()
            ProbeResult.Reachable
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            val status = responseStatusCode(e)
            if (status == 401 || status == 403) {
                ProbeResult.NeedsAuth
            } else {
                ProbeResult.Unreachable(friendlyErrorFor(e, profile.baseUrl))
            }
        } finally {
            runCatching { client.close() }
        }
    }

    // Declared last so every property above is fully initialized before these observers start.
    // They only launch coroutines into appScope (Dispatchers.IO) — nothing here runs
    // synchronously during construction — so placing init at the end removes any dependence on
    // the textual declaration order of the fields those observers reference.
    init {
        if (!skipInit) {
            NotificationChannels.create(appContext!!)
            observeMessageActivity()
            observeRunReconcileOnReconnect()
            observeRunForegroundService()
            observeBackgroundedConnectionFailures()
            // Load the persisted muted-sessions set so isSessionMuted() reflects the choice
            // immediately on cold start (before any session list composition).
            appScope.launch {
                runCatchingCancellable {
                    sessionPrefsStore.muted.collect { ids -> _mutedSessions.value = ids }
                }
            }
            // Flush right after load() populates the queue. observeOutbox()'s combine is keyed
            // on activeConnection/isOnline/_outboxFlushTrigger — NOT on outboxStore.messages — so
            // if connect() sets activeConnection before load() finishes reading a (possibly
            // multi-MB) outbox.json, the connect-triggered flush sees an empty queue and nothing
            // re-triggers it. Nudging the flush here guarantees a loaded queue is sent. Log a
            // load failure so a corrupt outbox.json / IO error is debuggable rather than silently
            // dropping every queued offline message until a future restart happens to succeed.
            appScope.launch {
                runCatchingCancellable { outboxStore.load() }
                    .onFailure { Log.w("AppContainer", "Outbox load failed; queued messages may be lost: ${safeExceptionSummary(it)}") }
                flushOutbox()
            }
            observeOutbox()
            appScope.launch { autoConnect() }
        }
    }
}

/**
 * Extract the session id a message-activity event pertains to, or null for events that
 * don't describe message activity. This drives the unread badge: any session that
 * receives activity while it isn't the currently-viewed one gets badged. Extracted as
 * a top-level `internal` function so the rule is unit-testable without an Android
 * [Context] (the surrounding [AppContainer] needs one).
 */
internal fun sessionOfEvent(event: BusEvent): String? = when (event) {
    is MessageUpdated -> event.properties.info.sessionID
    is MessagePartUpdated -> event.properties.part.sessionID ?: event.properties.sessionID
    else -> null
}

/**
 * The message id a message-activity event pertains to, used to de-duplicate the unread
 * badge so a reply's many streaming events count once. Null for events that carry no
 * message id (those fall back to being counted individually).
 */
internal fun messageIdOfEvent(event: BusEvent): String? = when (event) {
    is MessageUpdated -> event.properties.info.id
    is MessagePartUpdated -> event.properties.messageID ?: event.properties.part.messageID
    else -> null
}
