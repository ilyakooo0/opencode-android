package soy.iko.opencode.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServerListViewModel(private val container: AppContainer) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> =
        container.profileStore.profiles.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _connecting = MutableStateFlow<String?>(null)
    val connectingId: StateFlow<String?> = _connecting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    fun connect(profile: ServerProfile, onConnected: () -> Unit) {
        if (_connecting.value != null) return
        _connecting.value = profile.id
        _error.value = null
        viewModelScope.launch {
            val result = runCatching {
                val conn = container.connect(profile)
                conn.api.ping()
            }
            _connecting.value = null
            result.onSuccess { onConnected() }
                .onFailure {
                    container.disconnect()
                    _error.value = it.message ?: container.string(R.string.error_not_reachable, profile.baseUrl)
                }
        }
    }

    fun delete(profile: ServerProfile) {
        viewModelScope.launch { container.profileStore.delete(profile.id) }
    }
}
