package soy.iko.opencode.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionListState(
    val sessions: List<Session> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class SessionListViewModel(private val container: AppContainer) : ViewModel() {

    val serverLabel: String =
        container.activeConnection.value?.profile?.displayLabel ?: "opencode"

    private val _state = MutableStateFlow(SessionListState())
    val state: StateFlow<SessionListState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val conn = container.activeConnection.value
        if (conn == null) {
            _state.value = SessionListState(loading = false, error = "Not connected")
            return
        }
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
}
