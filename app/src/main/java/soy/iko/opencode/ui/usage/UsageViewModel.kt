package soy.iko.opencode.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.util.runCatchingCancellable
import soy.iko.opencode.util.safeExceptionSummary

/**
 * Computes a cross-session [UsageReport] by listing the server's sessions and fetching each
 * one's messages (bounded concurrency), then aggregating cost/tokens off the main thread.
 * There's no server-side usage endpoint, so this reconstructs totals from the per-message
 * cost/token fields the API already returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModel(private val container: AppContainer) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Disconnected : State
        /** Usage failed to load. [message] carries a short, safe summary (e.g. "offline",
         *  "server error") so the user can tell an auth failure from a transient network drop. */
        data class Error(val message: String? = null) : State
        data class Ready(val report: UsageReport) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True while a reload runs on top of an already-loaded report, so the existing figures stay
     *  on screen and only the pull-to-refresh spinner shows. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Bumped by [load] (refresh/retry) to re-run without waiting for a connection change. */
    private val _reload = MutableStateFlow(0)

    /** Active time-range filter. Changing it re-aggregates from the cached fetch (no network)
     *  so toggling ranges is instant. Defaults to all time. */
    private val _timeRange = MutableStateFlow(UsageTimeRange.ALL_TIME)
    val timeRange: StateFlow<UsageTimeRange> = _timeRange.asStateFlow()

    // Cached raw inputs from the last successful fetch, so a time-range change can re-aggregate
    // without re-downloading every session's history.
    @Volatile private var cachedInputs: List<UsageSessionInput>? = null

    init {
        // Observe the active connection so the report loads (or reloads) once a connection is
        // available — including when the screen opens during a reconnect window where
        // activeConnection.value is momentarily null (else it would latch Disconnected until a
        // manual refresh). collectLatest cancels an in-flight load when the connection changes or
        // a manual reload arrives, so the later-*starting* trigger always wins.
        viewModelScope.launch {
            merge(container.activeConnection, _reload).collectLatest { doLoad() }
        }
        // Re-aggregate from the cache when only the time range changes (no network round-trip).
        viewModelScope.launch {
            _timeRange.collectLatest { reaggregate() }
        }
    }

    fun load() { _reload.value++ }

    fun setTimeRange(range: UsageTimeRange) { _timeRange.value = range }

    /** Re-aggregate the cached fetch under the current time range, if a Ready report exists. */
    private suspend fun reaggregate() {
        // Snapshot the cached inputs and state together. Reading cachedInputs and _state.value
        // as two separate @Volatile reads is non-atomic: a concurrent doLoad() from a manual
        // reload can update cachedInputs and set State.Ready(newReport) in between, after which
        // reaggregate would pass the is-Ready check (now the new Ready) but aggregate the OLD
        // inputs — clobbering the user's fresh reload with stale totals. Capture both up front,
        // and re-check cachedInputs hasn't changed before publishing so a doLoad that landed
        // mid-aggregation wins instead.
        //
        // The is-Ready guard also serves the original purpose: a failed doLoad leaves state as
        // Error (with cachedInputs still populated from the prior successful fetch), and
        // reaggregating that stale cache would flip Error back to Ready — hiding the failure
        // and showing old totals as if the load succeeded. The user must retry (which clears
        // cachedInputs on its own success) to see fresh data again.
        val inputs = cachedInputs ?: return
        if (_state.value !is State.Ready) return
        val cutoff = _timeRange.value.cutoffMs()
        withContext(Dispatchers.Default) {
            val report = aggregateUsage(inputs, cutoff)
            // Skip the publish if a doLoad replaced the cache while we were aggregating —
            // that doLoad set a fresher State.Ready, and overwriting it here would regress
            // the report to the pre-reload inputs under the (possibly stale) cutoff.
            if (cachedInputs !== inputs) return@withContext
            _state.value = State.Ready(report)
        }
    }

    private suspend fun doLoad() {
        val conn = container.activeConnection.value ?: run {
            _state.value = State.Disconnected
            return
        }
        // Keep the current report visible during a manual reload; only blank to the full-screen
        // spinner on the first load.
        if (_state.value is State.Ready) _refreshing.value = true else _state.value = State.Loading
        try {
            // Fetch the raw per-session data first; the slow part is the network I/O, not the
            // aggregation. The cutoff is read AFTER the fetch completes (not before) so a time-
            // range change that arrives mid-fetch is honored: reaggregate() returns early while
            // state is Loading, so without this the report would be computed with the stale cutoff
            // and the user's new selection wouldn't apply until the next manual reload.
            val fetched = runCatchingCancellable {
                val sessions = conn.repository.listSessions()
                // Cap concurrent message fetches so a large history doesn't open dozens of
                // sockets at once; a per-session fetch failure yields an empty list rather
                // than aborting the whole report.
                val limiter = Semaphore(MAX_CONCURRENT_FETCHES)
                // coroutineScope so the per-session fetches are children of THIS load: when
                // collectLatest cancels a stale load (connection changed / manual reload), the
                // in-flight fetches are cancelled with it instead of leaking.
                coroutineScope {
                    sessions.map { session ->
                        async {
                            limiter.withPermit {
                                val messages = runCatchingCancellable { conn.api.listMessages(session.id) }
                                    .getOrDefault(emptyList())
                                UsageSessionInput(
                                    sessionId = session.id,
                                    title = session.displayTitle,
                                    modifiedAt = session.time?.updated ?: session.time?.created,
                                    messages = messages,
                                )
                            }
                        }
                    }.awaitAll()
                }
            }
            // On failure, set Error; the finally block resets _refreshing. On success, cache
            // the inputs and aggregate under the LATEST time-range cutoff (read after the fetch
            // so a range change that arrived mid-fetch is honored — reaggregate() returns early
            // while state is Loading, so without re-reading here the report would use the stale
            // cutoff and the user's new selection wouldn't apply until the next manual reload).
            if (fetched.isFailure) {
                _state.value = State.Error(safeExceptionSummary(fetched.exceptionOrNull()!!))
            } else {
                val inputs = fetched.getOrThrow()
                cachedInputs = inputs
                val cutoff = _timeRange.value.cutoffMs()
                val report = withContext(Dispatchers.Default) { aggregateUsage(inputs, cutoff) }
                _state.value = State.Ready(report)
            }
        } finally {
            // Reset in finally so a collectLatest cancellation (which rethrows
            // CancellationException out of runCatchingCancellable, skipping the lines above)
            // can't leave the pull-to-refresh spinner latched true.
            _refreshing.value = false
        }
    }

    private companion object {
        const val MAX_CONCURRENT_FETCHES = 6
    }
}
