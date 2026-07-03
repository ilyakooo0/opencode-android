package soy.iko.opencode.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.util.runCatchingCancellable

/** Loads the configured MCP servers from the active connection's `config` (read-only view). */
@OptIn(ExperimentalCoroutinesApi::class)
class McpViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Disconnected : State
        data object Error : State
        data class Ready(val servers: List<McpServerInfo>) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True while a reload runs on top of already-loaded data, so the list stays visible and the
     *  pull-to-refresh spinner (not the full-screen one) shows. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Bumped by [load] (refresh/retry) to re-run without waiting for a connection change. */
    private val _reload = MutableStateFlow(0)

    init {
        // Observe the active connection so the servers load (or reload) once a connection is
        // available — including when the screen opens during a reconnect window where
        // activeConnection.value is momentarily null (else it would latch Disconnected until a
        // manual refresh). collectLatest cancels an in-flight load when the connection changes or
        // a manual reload arrives.
        viewModelScope.launch {
            merge(container.activeConnection, _reload).collectLatest { doLoad() }
        }
    }

    fun load() { _reload.value++ }

    private suspend fun doLoad() {
        val conn = container.activeConnection.value ?: run {
            _state.value = State.Disconnected
            return
        }
        // Keep already-loaded servers on screen during a reload; only show the full-screen
        // spinner on the very first load.
        if (_state.value is State.Ready) _refreshing.value = true else _state.value = State.Loading
        runCatchingCancellable {
            val config = conn.api.config()
            withContext(Dispatchers.Default) { parseMcpServers(config) }
        }
            .onSuccess { _state.value = State.Ready(it) }
            .onFailure { _state.value = State.Error }
        _refreshing.value = false
    }
}
