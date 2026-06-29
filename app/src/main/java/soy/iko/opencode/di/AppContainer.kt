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

    init {
        registerNetworkMonitor()
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
