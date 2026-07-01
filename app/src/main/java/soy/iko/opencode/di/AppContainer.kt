package soy.iko.opencode.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.util.Log
import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.data.model.MessagePartUpdated
import soy.iko.opencode.data.model.MessageUpdated
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.SessionIdle
import soy.iko.opencode.data.repo.DraftStore
import soy.iko.opencode.data.repo.ErrorKind
import soy.iko.opencode.data.repo.ProfileStore
import soy.iko.opencode.data.repo.SettingsStore
import soy.iko.opencode.data.repo.classifyError
import soy.iko.opencode.data.repo.responseStatusCode
import soy.iko.opencode.data.network.HttpClientFactory
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.data.network.OpencodeApiClient
import soy.iko.opencode.notification.NotificationChannels
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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

    open fun setCurrentSession(id: String?) {
        _currentSession.value = id
        if (id != null) {
            _unread.update { it - id }
            appContext?.let { SessionNotifications.cancel(it, id) }
        }
    }

    /** Restore a session's unread badge (preserving its prior count) after a failed
     *  server switch reconnects. Falls back to a count of 1 when the prior count isn't
     *  known, since the restore path only runs for sessions that were badged before. */
    open fun restoreUnread(id: String) {
        if (id != _currentSession.value) {
            _unread.update { it + (id to (it[id] ?: 1)) }
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

    init {
        if (!skipInit) {
            NotificationChannels.create(appContext!!)
            observeMessageActivity()
            appScope.launch { autoConnect() }
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
        draftStore.let { if (!skipInit) it.shutdown() }
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
                    // SessionIdle is not "message activity" (don't badge as unread)
                    // but signals a run finished — fire a completion notification if the
                    // session isn't currently being viewed and was actively streaming.
                    if (event is SessionIdle) {
                        val idleSid = event.properties.sessionID ?: return@collect
                        if (idleSid != _currentSession.value && activeRuns.remove(idleSid)) {
                            _anyRunActive.value = activeRuns.isNotEmpty()
                            notifySessionCompleted(idleSid)
                        }
                        return@collect
                    }
                    val sid = sessionOf(event) ?: return@collect
                    if (sid != _currentSession.value) {
                        // Increment the unread count for this session so the badge can
                        // show "N unread" instead of just a dot.
                        _unread.update { it + (sid to (it[sid] ?: 0) + 1) }
                    }
                    // Track sessions actively streaming so we know which idle events
                    // represent a finished run worth notifying about.
                    if (event is MessagePartUpdated || event is MessageUpdated) {
                        synchronized(activeRuns) {
                            if (activeRuns.size >= activeRunsLimit) {
                                activeRuns.remove(activeRuns.iterator().next())
                            }
                            activeRuns.add(sid)
                        }
                        _anyRunActive.value = true
                    }
                } }.onFailure { Log.w("AppContainer", "Message activity observer failed, will retry: ${safeExceptionSummary(it)}") }
                if (!isActive) break
                delay(NetworkConfig.observerRetryDelayMs)
            }
        }
    }

    /** Session ids currently streaming an assistant run (best-effort, in-process). */
    private val activeRuns: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Upper bound on [activeRuns] to prevent unbounded growth if SessionIdle never arrives. */
    private val activeRunsLimit = NetworkConfig.activeRunsLimit

    /** Guards connect/disconnect so concurrent callers can't leak an old connection. */
    private val connectionMutex = Mutex()

    /** Only persist the `lastUsed` timestamp if it's older than this, to avoid
     *  a DataStore + encrypted-prefs write on every rapid reconnect. */
    private companion object {
        const val LAST_USED_SAVE_THRESHOLD_MS = 60_000L
    }

    /** Resolve the title for [sessionId] (best-effort) and post a completion notification. */
    private suspend fun notifySessionCompleted(sessionId: String) {
        val conn = activeConnection.value ?: return
        val title = runCatchingCancellable {
            conn.repository.listSessions().firstOrNull { it.id == sessionId }?.displayTitle
        }.getOrNull() ?: sessionId
        appContext?.let { SessionNotifications.postCompleted(it, sessionId, title) }
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
        val ok = runCatchingCancellable {
            val conn = connect(recent)
            conn.api.ping()
        }.isSuccess
        _reconnecting.value = false
        if (ok) _autoConnectDone.value = true else disconnect()
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
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
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
            activeRuns.clear()
            _anyRunActive.value = false
            _unread.value = emptyMap()
            val now = System.currentTimeMillis()
            val resolved = profileStore.resolve(profile)
            val needsSave = (now - resolved.lastUsed) > LAST_USED_SAVE_THRESHOLD_MS
            val finalProfile = if (needsSave) resolved.copy(lastUsed = now) else resolved
            if (needsSave) profileStore.save(finalProfile)
            OpencodeConnection(finalProfile).also { _activeConnection.value = it }
        }

    open suspend fun disconnect() {
        // Acquire the connection mutex so disconnect is serialized with connect().
        // Previously this was fire-and-forget (launch on appScope), which raced with
        // a subsequent connect(): connect() could acquire the mutex, create a new
        // connection, and then the pending disconnect() would close it.
        connectionMutex.withLock {
            _activeConnection.value?.close()
            _activeConnection.value = null
        }
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
