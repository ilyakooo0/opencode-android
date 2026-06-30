package soy.iko.opencode.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class ServerEditState(
    val id: String? = null,
    val label: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = baseUrl.isNotBlank()
    val isNew: Boolean get() = id == null
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
                val existing = withTimeoutOrNull(5_000) {
                    container.profileStore.profiles.first().firstOrNull { it.id == profileId }
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
            }
            _state.value = _state.value.copy(loaded = true)
        }
    }

    fun update(transform: (ServerEditState) -> ServerEditState) {
        _state.value = transform(_state.value)
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            val result = runCatching {
                val existingLastUsed = if (s.id != null) {
                    withTimeoutOrNull(5_000) {
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
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "Failed to save") }
        }
    }
}
