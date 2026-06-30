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
import soy.iko.opencode.notification.NotificationChannels
import soy.iko.opencode.notification.SessionNotifications
import soy.iko.opencode.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hand-written service locator held by [soy.iko.opencode.OpencodeApp]. Owns the
 * process-wide singletons and the currently active [OpencodeConnection].
 */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val profileStore = ProfileStore(context)
    val settingsStore = SettingsStore(context)
    val draftStore = DraftStore(context, appScope)

    private val appContext = context.applicationContext

    private val _activeConnection = MutableStateFlow<OpencodeConnection?>(null)
    val activeConnection: StateFlow<OpencodeConnection?> = _activeConnection.asStateFlow()

    /** True while a background auto-reconnect to the most-recent server is in flight. */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    /**
     * Set once a startup auto-reconnect succeeds. The server list collects this and
     * (once) navigates straight to the session list so a returning user skips it.
     * [consumeAutoConnect] guards against re-firing on screen re-entry.
     */
    private val _autoConnectDone = MutableStateFlow(false)
    val autoConnectDone: StateFlow<Boolean> = _autoConnectDone.asStateFlow()
    private val autoConnectConsumed = AtomicBoolean(false)
    fun consumeAutoConnect(): Boolean = autoConnectConsumed.compareAndSet(false, true)

    /** Text shared into the app (ACTION_SEND), prefilled into a session draft. */
    private val _pendingShare = MutableStateFlow<String?>(null)
    val pendingShare: StateFlow<String?> = _pendingShare.asStateFlow()
    fun setPendingShare(text: String) { _pendingShare.value = text }
    fun consumePendingShare(): String? {
        val current = _pendingShare.value ?: return null
        return if (_pendingShare.compareAndSet(current, null)) current else null
    }

    /**
     * A session id to open from an external trigger (a notification tap or a deep link).
     * The nav host consumes it once a connection is active.
     */
    private val _pendingOpenSession = MutableStateFlow<String?>(null)
    val pendingOpenSession: StateFlow<String?> = _pendingOpenSession.asStateFlow()
    fun requestOpenSession(id: String) { _pendingOpenSession.value = id }
    fun consumePendingOpenSession(): String? {
        val current = _pendingOpenSession.value ?: return null
        return if (_pendingOpenSession.compareAndSet(current, null)) current else null
    }

    /**
     * The session the user is currently viewing (or null when not in a chat). Drives the
     * unread tracker: messages arriving for any *other* session mark it unread, and
     * opening a session clears its unread state.
     */
    private val _currentSession = MutableStateFlow<String?>(null)
    val currentSession: StateFlow<String?> = _currentSession.asStateFlow()

    /** Session ids that received activity while not being viewed. */
    private val _unread = MutableStateFlow<Set<String>>(emptySet())
    val unread: StateFlow<Set<String>> = _unread.asStateFlow()

    fun setCurrentSession(id: String?) {
        _currentSession.value = id
        if (id != null) {
            _unread.update { it - id }
            SessionNotifications.cancel(appContext, id)
        }
    }

    /** Resolve a localized string — view models reach resources through the container. */
    fun string(id: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) appContext.getString(id) else appContext.getString(id, *formatArgs)

    /**
     * Convert a throwable into a user-facing message. Classifies the error by concrete
     * type (network, timeout, HTTP status) rather than string-matching class names, so
     * the message reflects what actually went wrong without leaking internal URLs/state.
     */
    fun friendlyError(t: Throwable): String {
        val baseUrl = activeConnection.value?.profile?.baseUrl.orEmpty()
        return when (classifyError(t)) {
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
    }

    init {
        NotificationChannels.create(appContext)
        observeMessageActivity()
        appScope.launch { autoConnect() }
    }

    /** Release resources held for the process lifetime (network callback, app scope). */
    fun shutdown() {
        _activeConnection.value?.close()
        _activeConnection.value = null
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        appScope.cancel()
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
            activeConnection
                .flatMapLatest { conn -> conn?.events?.events ?: emptyFlow() }
                .collect { event ->
                    // SessionIdle is not "message activity" (don't badge as unread)
                    // but signals a run finished — fire a completion notification if the
                    // session isn't currently being viewed and was actively streaming.
                    if (event is SessionIdle) {
                        val idleSid = event.properties.sessionID ?: return@collect
                        if (idleSid != _currentSession.value && activeRuns.remove(idleSid)) {
                            notifySessionCompleted(idleSid)
                        }
                        return@collect
                    }
                    val sid = sessionOf(event) ?: return@collect
                    if (sid != _currentSession.value) {
                        _unread.update { it + sid }
                    }
                    // Track sessions actively streaming so we know which idle events
                    // represent a finished run worth notifying about.
                    if (event is MessagePartUpdated || event is MessageUpdated) {
                        activeRuns.add(sid)
                    }
                }
        }
    }

    /** Session ids currently streaming an assistant run (best-effort, in-process). */
    private val activeRuns: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

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
        val title = runCatching {
            conn.repository.listSessions().firstOrNull { it.id == sessionId }?.displayTitle
        }.getOrNull() ?: sessionId
        SessionNotifications.postCompleted(appContext, sessionId, title)
    }

    /** Extract the session id an event pertains to, for message-activity events. */
    private fun sessionOf(event: BusEvent): String? = sessionOfEvent(event)

    /**
     * On cold start, transparently reconnect to the most recently used server so a
     * returning user lands in their session list instead of the empty server screen.
     */
    private suspend fun autoConnect() {
        val recent = runCatching { profileStore.profiles.first() }
            .getOrDefault(emptyList())
            .firstOrNull() ?: return
        _reconnecting.value = true
        val ok = runCatching {
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
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeConnection.value?.events?.triggerReconnect()
            }
        }
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
            .onFailure { Log.w("AppContainer", "Failed to register network callback", it) }
        return callback
    }

    /** Open (or re-open) a connection to [profile], replacing any current one.
     *  Only persists the updated `lastUsed` timestamp if it's stale (older than a
     *  minute), so rapid reconnect storms don't each trigger a DataStore write. */
    suspend fun connect(profile: ServerProfile): OpencodeConnection =
        connectionMutex.withLock {
            _activeConnection.value?.close()
            _activeConnection.value = null
            val now = System.currentTimeMillis()
            val resolved = profileStore.resolve(profile)
            val needsSave = (now - resolved.lastUsed) > LAST_USED_SAVE_THRESHOLD_MS
            val finalProfile = if (needsSave) resolved.copy(lastUsed = now) else resolved
            if (needsSave) profileStore.save(finalProfile)
            OpencodeConnection(finalProfile).also { _activeConnection.value = it }
        }

    fun disconnect() {
        // Non-suspending best-effort disconnect; if a connect is in progress under the
        // mutex, the connection it sets will be closed on the next disconnect/connect.
        val conn = _activeConnection.value ?: return
        _activeConnection.value = null
        conn.close()
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
