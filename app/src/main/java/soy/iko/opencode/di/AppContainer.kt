package soy.iko.opencode.di

import android.content.Context
import soy.iko.opencode.data.model.ServerProfile
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

    private val _activeConnection = MutableStateFlow<OpencodeConnection?>(null)
    val activeConnection: StateFlow<OpencodeConnection?> = _activeConnection.asStateFlow()

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
