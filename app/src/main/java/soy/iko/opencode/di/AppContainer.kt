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
import soy.iko.opencode.data.repo.AttachmentDraftStore
import soy.iko.opencode.data.repo.BackupManager
import soy.iko.opencode.data.repo.DraftStore
import soy.iko.opencode.data.repo.ErrorKind
import soy.iko.opencode.data.repo.MessageCacheStore
import soy.iko.opencode.data.repo.OutboxMessage
import soy.iko.opencode.data.repo.OutboxStore
import soy.iko.opencode.data.repo.SessionPrefsStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flatMapLatest
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
     * consumes it once connected, creating a fresh session and opening it.
     */
    private val _pendingNewSession = MutableStateFlow(false)
    open val pendingNewSession: StateFlow<Boolean> = _pendingNewSession.asStateFlow()
    open fun requestNewSession() { _pendingNewSession.value = true }
    open fun consumePendingNewSession(): Boolean = _pendingNewSession.compareAndSet(true, false)

    /**
     * A session id to open from an external trigger (a notification tap or a deep link).
     * The nav host consumes it once a connection is active.
     */
    private val _pendingOpenSession = MutableStateFlow<String?>(null)
    open val pendingOpenSession: StateFlow<String?> = _pendingOpenSession.asStateFlow()
    open fun requestOpenSession(id: String) { _pendingOpenSession.value = id }
    open fun consumePendingOpenSession(): String? {
        val current = _pendingOpenSession.value ?: return null
        return if (_pendingOpenSession.compareAndSet(current, null)) current else null
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

    /** True when any assistant run is actively streaming across all sessions. Drives the
     *  disconnect confirmation on the session list (disconnecting mid-run kills it). */
    private val _anyRunActive = MutableStateFlow(false)
    open val anyRunActive: StateFlow<Boolean> = _anyRunActive.asStateFlow()

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
            ErrorKind.CLIENT -> responseStatusCode(t)?.let { string(R.string.error_client_status, it) }
                ?: string(R.string.error_generic)
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
                    if (active) RunForegroundService.start(ctx) else RunForegroundService.stop(ctx)
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
                                _anyRunActive.value = false
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
                val status = result.exceptionOrNull()?.let { responseStatusCode(it) }
                // Permanent = a non-408/429 4xx, matching withRetry's non-retryable rule.
                val permanent = when (status) {
                    null, 408, 429 -> false
                    in 400..499 -> true
                    else -> false
                }
                if (permanent) {
                    Log.w("AppContainer", "Dropping undeliverable outbox message ${msg.id} for ${msg.sessionId} (HTTP $status)")
                    outboxStore.remove(msg.id)
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
        // Bounded runBlocking so a stuck mutex or a slow close() can't ANR the app.
        // 2 seconds is generous: appScope.cancel() already triggered cancellation of
        // any coroutine holding the mutex; we're just waiting for it to unwind.
        kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(2_000) {
                connectionMutex.withLock {
                    runCatching { _activeConnection.value?.close() }
                    _activeConnection.value = null
                }
            }
            // If the timeout fired, force-clear the connection so it isn't left dangling.
            _activeConnection.value = null
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
                        .flatMapLatest { conn -> conn?.events?.events ?: emptyFlow() }
                        .collect { event ->
                    // A permission request needs the user's approval. Post a heads-up
                    // notification (with Allow/Reject actions) unless the user is actively
                    // viewing this session, where the in-app permission dialog handles it.
                    if (event is PermissionUpdated) {
                        val psid = event.properties.sessionID.takeIf { it.isNotBlank() } ?: return@collect
                        if (!isActivelyViewing(psid)) appScope.launch { notifyPermission(event.properties) }
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
                                if (removed) _anyRunActive.value = activeRuns.isNotEmpty()
                            }
                        }
                        if (wasRunning && !isActivelyViewing(esid)) {
                            appScope.launch { notifySessionError(esid) }
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
                                if (removed) _anyRunActive.value = activeRuns.isNotEmpty()
                            }
                        }
                        if (wasRunning && !isActivelyViewing(idleSid)) {
                            // Fire this off the collector's coroutine: notifySessionCompleted
                            // does a network listSessions() to resolve the title, and blocking
                            // the shared-event collector here would stall the SharedFlow,
                            // overflowing its DROP_OLDEST buffer and silently dropping live
                            // parts/updates for every other subscriber (e.g. the message reducer).
                            appScope.launch { notifySessionCompleted(idleSid) }
                        }
                        return@collect
                    }
                    val sid = sessionOf(event) ?: return@collect
                    // Re-read currentSession here, right before mutating the badge state:
                    // the user may have opened this session between when this event was
                    // enqueued and now (setCurrentSession clears its badge and dedup set).
                    // A stale earlier check would race a badge — and a dedup entry — back
                    // in for the session that's actually on screen.
                    if (sid != _currentSession.value) {
                        // Increment the unread count once per distinct *message*, not per
                        // event: a single reply emits many message.updated / message.part.
                        // updated events (one per streamed token, plus cost/token refreshes),
                        // which would otherwise inflate the badge into the dozens/hundreds.
                        val messageId = messageIdOfEvent(event)
                        val counted = unreadMessageIds.getOrPut(sid) {
                            java.util.Collections.synchronizedSet(mutableSetOf())
                        }
                        if (messageId == null || counted.add(messageId)) {
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
                        // The add-under-lock is required both for thread-safety and for the
                        // LRU access-order refresh (keeps a continuously-streaming session
                        // from being evicted), so it runs per event.
                        synchronized(activeRuns) {
                            if (activeRuns.size >= activeRunsLimit) {
                                activeRuns.remove(activeRuns.iterator().next())
                            }
                            activeRuns.add(sid)
                            // Derive the flag from the set state *inside* the same lock that
                            // guards the set, so a concurrent reconnect sweep (which clears
                            // both under this lock) can't interleave and leave the flag pinned
                            // true with an empty set — a stuck foreground service + "working…".
                            _anyRunActive.value = activeRuns.isNotEmpty()
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
        val info = (event as? MessageUpdated)?.properties?.info
        if (info is AssistantMessage && info.isComplete) return false
        // A step-finish part is the trailing completion marker for a run (it carries the
        // final cost/token totals) and arrives after the last stream delta. Like a completed
        // message.updated, treating it as live would re-add the session to activeRuns with no
        // following SessionIdle to clear it — pinning anyRunActive true indefinitely when such
        // a part is replayed/reordered after the run already went idle.
        val part = (event as? MessagePartUpdated)?.properties?.part
        if (part is StepFinishPart) return false
        return true
    }

    /** Session ids currently streaming an assistant run (best-effort, in-process). Backed by
     *  an access-order [java.util.LinkedHashMap] so re-adding an already-present id on each
     *  streaming event refreshes its recency (a plain LinkedHashSet.add is a no-op that does
     *  NOT reorder). This keeps a long, continuously-streaming session from being evicted as
     *  the eldest once [activeRunsLimit] other sessions appear — which would drop its
     *  completion notification when its SessionIdle finally arrives. */
    private val activeRuns: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.LinkedHashMap<String, Boolean>(16, 0.75f, true)),
    )

    /** Per non-viewed session, the set of message ids already reflected in its unread
     *  count. De-duplicates the many streaming events that share one message id so the
     *  badge counts messages, not deltas. Cleared when a session is viewed or on connect. */
    private val unreadMessageIds = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** Upper bound on [activeRuns] to prevent unbounded growth if SessionIdle never arrives. */
    private val activeRunsLimit = NetworkConfig.activeRunsLimit

    /** Guards connect/disconnect so concurrent callers can't leak an old connection. */
    private val connectionMutex = Mutex()

    /** Only persist the `lastUsed` timestamp if it's older than this, to avoid
     *  a DataStore + encrypted-prefs write on every rapid reconnect. */
    private companion object {
        const val LAST_USED_SAVE_THRESHOLD_MS = 60_000L
    }

    /** Resolve the human-readable title for [sessionId] (best-effort), falling back to the id. */
    private suspend fun resolveSessionTitle(sessionId: String): String =
        runCatchingCancellable {
            activeConnection.value?.repository?.listSessions()?.firstOrNull { it.id == sessionId }?.displayTitle
        }.getOrNull() ?: sessionId

    /** Resolve the title for [sessionId] (best-effort) and post a completion notification. */
    private suspend fun notifySessionCompleted(sessionId: String) {
        val title = resolveSessionTitle(sessionId)
        appContext?.let { SessionNotifications.postCompleted(it, sessionId, title) }
    }

    /** Post a heads-up permission notification for [permission], resolving its session title. */
    private suspend fun notifyPermission(permission: Permission) {
        val sessionId = permission.sessionID.takeIf { it.isNotBlank() } ?: return
        val title = resolveSessionTitle(sessionId)
        appContext?.let { SessionNotifications.postPermission(it, permission, title) }
    }

    /** Post an error notification for a failed background run. */
    private suspend fun notifySessionError(sessionId: String) {
        val title = resolveSessionTitle(sessionId)
        appContext?.let { SessionNotifications.postError(it, sessionId, title) }
    }

    /** Respond to a permission from a notification action (Allow once / Always / Reject),
     *  off any UI. Runs on the process-lived app scope; [onDone] fires when the call resolves
     *  so the receiver's goAsync() result can finish. Its Boolean is true only when a live
     *  connection existed AND respondPermission succeeded — the receiver uses it to decide
     *  whether to dismiss the notification. A missing connection makes the respond a no-op,
     *  so it must report failure (not success) rather than let the notification vanish with
     *  the tool left unanswered. */
    open fun respondToPermissionFromNotification(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        onDone: (Boolean) -> Unit,
    ) {
        appScope.launch {
            val success = runCatchingCancellable {
                val api = activeConnection.value?.api ?: return@runCatchingCancellable false
                api.respondPermission(sessionId, permissionId, response)
                true
            }.onFailure {
                Log.w("AppContainer", "notif permission respond failed: ${safeExceptionSummary(it)}")
            }.getOrDefault(false)
            onDone(success)
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
    open fun sendPromptFromNotification(sessionId: String, text: String, onDone: (Boolean) -> Unit) {
        appScope.launch {
            // Attribute to the active (or most-recent) profile so a reconnect to a different
            // server doesn't misfire this against a session id that only exists elsewhere.
            val profileId = activeConnection.value?.profile?.id
                ?: runCatchingCancellable { profileStore.profiles.first().firstOrNull()?.id }.getOrNull()
            val enqueued = if (profileId == null) {
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
            onDone(enqueued)
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
        _reconnecting.value = true
        var conn: OpencodeConnection? = null
        val ok = runCatchingCancellable {
            conn = connect(recent)
            conn!!.api.ping()
        }.isSuccess
        _reconnecting.value = false
        if (ok) {
            _autoConnectDone.value = true
        } else {
            val created = conn
            // Only tear down the connection we created: a manual connect to a different server
            // during the ping window may have already replaced it, and disconnect() closes
            // whatever is currently active — which would silently drop that new connection.
            // disconnectIf re-checks identity under the mutex to close the check-then-close race.
            if (created != null) disconnectIf(created)
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
            override fun onLost(network: Network) { _isOnline.value = cm.activeNetwork != null }
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
        connectionMutex.withLock {
            _activeConnection.value?.close()
            _activeConnection.value = null
            // Mutate the run set and its derived flag together under activeRuns' monitor: the SSE
            // event handler adds ids under the same lock, so an unlocked clear() here could
            // interleave with an add()+flag=true and leave _anyRunActive pinned true with a torn-
            // down connection and no SessionIdle to clear it — a stuck foreground service.
            synchronized(activeRuns) {
                activeRuns.clear()
                _anyRunActive.value = false
            }
            _unread.value = emptyMap()
            unreadMessageIds.clear()
            val now = System.currentTimeMillis()
            val resolved = profileStore.resolve(profile)
            val needsSave = (now - resolved.lastUsed) > LAST_USED_SAVE_THRESHOLD_MS
            val finalProfile = if (needsSave) resolved.copy(lastUsed = now) else resolved
            if (needsSave) profileStore.save(finalProfile)
            OpencodeConnection(finalProfile, messageCacheStore).also { _activeConnection.value = it }
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
            _anyRunActive.value = false
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
        pendingProfileDeletes.put(id, job)?.cancel()
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
                        messageCacheStore.remove(id)
                        outboxStore.removeForSession(id)
                        onDeleted()
                    }
                    .onFailure { onError(it) }
            }
        }
        pendingSessionDeletes.put(id, job)?.cancel()
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
    open suspend fun probeServer(baseUrl: String): ProbeResult {
        return probeWithProfile(ServerProfile(
            id = "probe",
            label = "",
            baseUrl = baseUrl.trim(),
            username = null,
            password = null,
        ))
    }

    /**
     * Probe a server URL *with* credentials to validate them. Returns true if the
     * server accepts the credentials (ping succeeds), false on 401/403, and throws
     * on other failures (unreachable, timeout) so the caller can surface a friendly
     * message. Mirrors [probeServer]'s short-lived-client pattern.
     */
    open suspend fun probeWithCredentials(baseUrl: String, username: String, password: String): Boolean {
        val probeProfile = ServerProfile(
            id = "probe-auth",
            label = "",
            baseUrl = baseUrl.trim(),
            username = username.trim().takeIf { it.isNotBlank() },
            password = password.trim().takeIf { it.isNotEmpty() },
        )
        val client = HttpClientFactory.create(probeProfile)
        return try {
            val api = OpencodeApiClient(client)
            api.ping()
            true
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
            // Flush right after load() populates the queue. observeOutbox()'s combine is keyed
            // on activeConnection/isOnline/_outboxFlushTrigger — NOT on outboxStore.messages — so
            // if connect() sets activeConnection before load() finishes reading a (possibly
            // multi-MB) outbox.json, the connect-triggered flush sees an empty queue and nothing
            // re-triggers it. Nudging the flush here guarantees a loaded queue is sent.
            appScope.launch {
                runCatchingCancellable { outboxStore.load() }
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
