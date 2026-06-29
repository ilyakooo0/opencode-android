package soy.iko.opencode.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionListState(
    val sessions: List<Session> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class SessionListViewModel(private val container: AppContainer) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> =
        container.profileStore.profiles.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _serverLabel = MutableStateFlow(
        container.activeConnection.value?.profile?.displayLabel ?: "opencode"
    )
    val serverLabel: StateFlow<String> = _serverLabel.asStateFlow()

    private val _switchingId = MutableStateFlow<String?>(null)
    val switchingId: StateFlow<String?> = _switchingId.asStateFlow()

    private val _state = MutableStateFlow(SessionListState())
    val state: StateFlow<SessionListState> = _state.asStateFlow()

    private val activeProfileId: String?
        get() = container.activeConnection.value?.profile?.id

    init { refresh() }

    fun refresh() {
        val conn = container.activeConnection.value
        if (conn == null) {
            _state.value = SessionListState(loading = false, error = "Not connected")
            return
        }
        _serverLabel.value = conn.profile.displayLabel
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { conn.repository.listSessions() }
                .onSuccess { list ->
                    _state.value = SessionListState(
                        sessions = list.sortedByDescending { it.time?.updated ?: it.time?.created ?: 0 },
                        loading = false,
                    )
                }
                .onFailure { _state.value = SessionListState(loading = false, error = it.message ?: "Failed to load sessions") }
        }
    }

    fun createSession(onCreated: (String) -> Unit) {
        val conn = container.activeConnection.value ?: return
        viewModelScope.launch {
            runCatching { conn.repository.createSession() }
                .onSuccess { onCreated(it.id); refresh() }
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "Failed to create session") }
        }
    }

    fun deleteSession(session: Session) {
        val conn = container.activeConnection.value ?: return
        viewModelScope.launch {
            runCatching { conn.repository.deleteSession(session.id) }
                .onSuccess { refresh() }
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "Failed to delete session") }
        }
    }

    /** Quick-switch to a different saved server without leaving the session list. */
    fun switchServer(profile: ServerProfile) {
        if (profile.id == activeProfileId) return
        if (_switchingId.value != null) return
        _switchingId.value = profile.id
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = runCatching {
                val conn = container.connect(profile)
                conn.api.ping()
            }
            _switchingId.value = null
            result.onSuccess { refresh() }
                .onFailure {
                    container.disconnect()
                    _state.value = SessionListState(loading = false, error = it.message ?: "Could not reach ${profile.baseUrl}")
                    refresh()
                }
        }
    }
}
