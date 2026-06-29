package soy.iko.opencode.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.data.model.MessagePartUpdated
import soy.iko.opencode.data.model.MessageUpdated
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.repo.DraftStore
import soy.iko.opencode.data.repo.ErrorKind
import soy.iko.opencode.data.repo.ProfileStore
import soy.iko.opencode.data.repo.SettingsStore
import soy.iko.opencode.data.repo.classifyError
import soy.iko.opencode.data.repo.responseStatusCode
import soy.iko.opencode.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hand-written service locator held by [soy.iko.opencode.OpencodeApp]. Owns the
 * process-wide singletons and the currently active [OpencodeConnection].
 */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val profileStore = ProfileStore(context)
    val settingsStore = SettingsStore(context)
    val draftStore = DraftStore(context)

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
    fun consumePendingShare(): String? = _pendingShare.value.also { _pendingShare.value = null }

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
        if (id != null) _unread.update { it - id }
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
        registerNetworkMonitor()
        observeMessageActivity()
        appScope.launch { autoConnect() }
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
                    val sid = sessionOf(event) ?: return@collect
                    if (sid != _currentSession.value) {
                        _unread.update { it + sid }
                    }
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
    private fun registerNetworkMonitor() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    activeConnection.value?.events?.triggerReconnect()
                }
            })
        }
    }

    /** Open (or re-open) a connection to [profile], replacing any current one. */
    suspend fun connect(profile: ServerProfile): OpencodeConnection {
        disconnect()
        val resolved = profileStore.resolve(profile).copy(lastUsed = System.currentTimeMillis())
        profileStore.save(resolved)
        return OpencodeConnection(resolved).also { _activeConnection.value = it }
    }

    fun disconnect() {
        _activeConnection.value?.close()
        _activeConnection.value = null
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
