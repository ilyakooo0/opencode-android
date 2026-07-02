package soy.iko.opencode.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.util.runCatchingCancellable

/** Loads the configured MCP servers from the active connection's `config` (read-only view). */
class McpViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Disconnected : State
        data object Error : State
        data class Ready(val servers: List<McpServerInfo>) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    init { load() }

    fun load() {
        val conn = container.activeConnection.value ?: run {
            _state.value = State.Disconnected
            return
        }
        _state.value = State.Loading
        viewModelScope.launch {
            runCatchingCancellable {
                val config = conn.api.config()
                withContext(Dispatchers.Default) { parseMcpServers(config) }
            }
                .onSuccess { _state.value = State.Ready(it) }
                .onFailure { _state.value = State.Error }
        }
    }
}
