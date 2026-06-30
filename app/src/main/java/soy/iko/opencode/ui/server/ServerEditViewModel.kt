package soy.iko.opencode.ui.server

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import soy.iko.opencode.R
import soy.iko.opencode.util.runCatchingCancellable
import java.util.UUID

@Immutable
data class ServerEditState(
    val id: String? = null,
    val label: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = baseUrl.isNotBlank() && isValidUrl(baseUrl)
    val isNew: Boolean get() = id == null
}

private fun isValidUrl(url: String): Boolean = try {
    val u = java.net.URI(url.trim())
    u.scheme != null && u.host != null && (u.scheme == "http" || u.scheme == "https")
} catch (_: Exception) {
    false
}

class ServerEditViewModel(
    private val container: AppContainer,
    private val profileId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerEditState(id = profileId))
    val state: StateFlow<ServerEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (profileId != null) {
                // Wait for a non-empty profile list before searching, so DataStore
                // emitting an empty list during loading doesn't cause a false timeout.
                val existing = withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                    container.profileStore.profiles
                        .first { it.isNotEmpty() || it.none { p -> p.id == profileId } }
                        .firstOrNull { it.id == profileId }
                }
                if (existing != null) {
                    _state.value = ServerEditState(
                        id = existing.id,
                        label = existing.label,
                        baseUrl = existing.baseUrl,
                        username = existing.username.orEmpty(),
                        password = existing.password.orEmpty(),
                        loaded = true,
                    )
                    return@launch
                }
                // Profile existed but timed out loading — show an error instead of
                // silently presenting an empty form that looks like "new server".
                _state.value = ServerEditState(
                    id = profileId,
                    loaded = true,
                    error = container.string(R.string.error_load_timeout),
                )
                return@launch
            }
            _state.value = _state.value.copy(loaded = true)
        }
    }

    fun update(transform: (ServerEditState) -> ServerEditState) {
        _state.value = transform(_state.value)
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave || s.saving) return
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            val result = runCatchingCancellable {
                val existingLastUsed = if (s.id != null) {
                    withTimeoutOrNull(NetworkConfig.profileLoadTimeoutMs) {
                        container.profileStore.profiles.first()
                            .firstOrNull { it.id == s.id }?.lastUsed
                    } ?: 0L
                } else 0L
                container.profileStore.save(
                    ServerProfile(
                        id = s.id ?: UUID.randomUUID().toString(),
                        label = s.label.trim(),
                        baseUrl = s.baseUrl.trim(),
                        username = s.username.trim().takeIf { it.isNotBlank() },
                        password = s.password.trim().takeIf { it.isNotEmpty() },
                        lastUsed = existingLastUsed,
                    ),
                )
            }
            result.onSuccess { onDone() }
                .onFailure { _state.value = _state.value.copy(error = container.friendlyError(it), saving = false) }
        }
    }
}
