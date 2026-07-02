package soy.iko.opencode.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.util.runCatchingCancellable

/**
 * Computes a cross-session [UsageReport] by listing the server's sessions and fetching each
 * one's messages (bounded concurrency), then aggregating cost/tokens off the main thread.
 * There's no server-side usage endpoint, so this reconstructs totals from the per-message
 * cost/token fields the API already returns.
 */
class UsageViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Disconnected : State
        data object Error : State
        data class Ready(val report: UsageReport) : State
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
                val sessions = conn.repository.listSessions()
                // Cap concurrent message fetches so a large history doesn't open dozens of
                // sockets at once; a per-session fetch failure yields an empty list rather
                // than aborting the whole report.
                val limiter = Semaphore(MAX_CONCURRENT_FETCHES)
                val perSession = sessions.map { session ->
                    async {
                        limiter.withPermit {
                            val messages = runCatchingCancellable { conn.api.listMessages(session.id) }
                                .getOrDefault(emptyList())
                            Triple(session.id, session.displayTitle, messages)
                        }
                    }
                }.awaitAll()
                withContext(Dispatchers.Default) { aggregateUsage(perSession) }
            }
                .onSuccess { _state.value = State.Ready(it) }
                .onFailure { _state.value = State.Error }
        }
    }

    private companion object {
        const val MAX_CONCURRENT_FETCHES = 6
    }
}
