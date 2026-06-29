package soy.iko.opencode.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.repo.DraftStore
import soy.iko.opencode.data.repo.ProfileStore
import soy.iko.opencode.data.repo.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    /** Resolve a localized string — view models reach resources through the container. */
    fun string(id: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) appContext.getString(id) else appContext.getString(id, *formatArgs)

    init {
        registerNetworkMonitor()
        appScope.launch { autoConnect() }
    }

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
