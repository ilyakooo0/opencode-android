package soy.iko.opencode.ui.search

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.R
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.util.runCatchingCancellable
import soy.iko.opencode.util.safeExceptionSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** One search result: the [session] that matched, a [snippet] of the matching text, the total
 *  number of [matchCount] occurrences (across body + title), and whether the title itself matched
 *  ([matchedTitle]) so the UI can highlight it like the snippet. */
@Immutable
data class SearchHit(
    val session: Session,
    val snippet: String,
    val matchCount: Int = 1,
    val matchedTitle: Boolean = false,
)

@Immutable
data class GlobalSearchState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SearchHit> = emptyList(),
    val error: String? = null,
    /** True once a search has run for the current query (distinguishes "no results" from
     *  "nothing searched yet" so the UI shows the right empty state). */
    val hasSearched: Boolean = false,
    /** True when there were more sessions than [NetworkConfig.maxSearchSessions], so the
     *  search only covered the most recent ones. */
    val truncated: Boolean = false,
)

/**
 * Cross-session message search. opencode's `/find` searches project *files*, not chat
 * history, so message search is done client-side: fetch each session's messages (bounded and
 * concurrent) and match their text. Runs only on an explicit, debounced query since it
 * downloads history.
 */
class GlobalSearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(GlobalSearchState())
    val state: StateFlow<GlobalSearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    // In-memory caches for the ViewModel's lifetime so a growing/re-typed query re-filters
    // locally instead of re-downloading every session's history on each debounced keystroke.
    // Only successful fetches are cached (a failed one isn't, so a later query retries it); an
    // explicit retry() clears both to force a fresh fetch. ConcurrentHashMap because the
    // per-session fetches run concurrently under the semaphore.
    @Volatile private var sessionsCache: List<Session>? = null
    private val messageCache = ConcurrentHashMap<String, List<MessageWithParts>>()

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < NetworkConfig.minSearchQueryLength) {
            _state.update { it.copy(searching = false, results = emptyList(), hasSearched = false, error = null) }
            return
        }
        // Show the spinner during the debounce (not just once runSearch starts) so the UI
        // doesn't briefly render an empty result set before the search begins.
        _state.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            delay(NetworkConfig.searchDebounceMs)
            runSearch(trimmed)
        }
    }

    /** Re-run the current query immediately (no debounce), used by the error-state Retry. Clears
     *  the caches so the retry actually refetches (the prior attempt may have failed). */
    fun retry() {
        val trimmed = _state.value.query.trim()
        if (trimmed.length < NetworkConfig.minSearchQueryLength) return
        sessionsCache = null
        messageCache.clear()
        searchJob?.cancel()
        _state.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch { runSearch(trimmed) }
    }

    private suspend fun runSearch(query: String) {
        val conn = container.activeConnection.value
        if (conn == null) {
            _state.update { it.copy(searching = false, error = container.string(R.string.not_connected)) }
            return
        }
        _state.update { it.copy(searching = true, error = null) }
        val sessions = sessionsCache
            ?: runCatchingCancellable { conn.repository.listSessions() }.getOrElse {
                _state.update { s -> s.copy(searching = false, error = container.friendlyError(it)) }
                return
            }.also { sessionsCache = it }
        val toSearch = sessions.take(NetworkConfig.maxSearchSessions)
        val truncated = sessions.size > toSearch.size
        val hits = java.util.Collections.synchronizedMap(HashMap<String, SearchHit>())
        val semaphore = Semaphore(NetworkConfig.maxConcurrentPreviews)
        coroutineScope {
            toSearch.forEach { session ->
                launch {
                    semaphore.withPermit {
                        // Reuse cached history when present so re-typing a query never re-fetches;
                        // only sessions not yet cached hit the network.
                        val messages = messageCache[session.id]
                            ?: runCatchingCancellable { conn.api.listMessages(session.id) }
                                .onSuccess { messageCache[session.id] = it }
                                .getOrElse {
                                    Log.w("GlobalSearch", "listMessages failed: " + safeExceptionSummary(it))
                                    emptyList()
                                }
                        val snippet = matchSnippet(messages, query)
                        val titleMatched = session.displayTitle.contains(query, ignoreCase = true)
                        if (snippet != null || titleMatched) {
                            val count = countMatches(messages, query) +
                                countIn(session.displayTitle, query)
                            hits[session.id] = SearchHit(
                                session = session,
                                snippet = snippet
                                    ?: session.displayTitle.takeIf { titleMatched }.orEmpty(),
                                matchCount = count.coerceAtLeast(1),
                                matchedTitle = titleMatched,
                            )
                        }
                    }
                }
            }
        }
        // Preserve the session order (already recency-sorted by the server) in the results.
        val ordered = toSearch.mapNotNull { hits[it.id] }
        _state.update {
            // If the query changed while this search was finishing (e.g. the user cleared the
            // box), cancelling this job can race the final write; only publish results for the
            // query still displayed so a stale result set can't land after the reset.
            if (it.query.trim() != query) it
            else it.copy(searching = false, results = ordered, hasSearched = true, truncated = truncated)
        }
    }

    /** Find the first text part containing [query] across [messages] and return a short
     *  snippet centered on the match, or null if nothing matches. */
    private fun matchSnippet(
        messages: List<soy.iko.opencode.data.model.MessageWithParts>,
        query: String,
    ): String? {
        for (message in messages) {
            for (part in message.parts) {
                if (part !is TextPart) continue
                val idx = part.text.indexOf(query, ignoreCase = true)
                if (idx >= 0) return buildSnippet(part.text, idx, query.length)
            }
        }
        return null
    }

    private fun buildSnippet(text: String, matchStart: Int, matchLength: Int): String {
        val window = NetworkConfig.searchSnippetLength
        val start = (matchStart - window / 2).coerceAtLeast(0)
        val end = (matchStart + matchLength + window / 2).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    /** Count all occurrences of [query] across the text parts of [messages] (case-insensitive,
     *  non-overlapping) so a result card can show "N matches" instead of just one snippet. */
    private fun countMatches(
        messages: List<soy.iko.opencode.data.model.MessageWithParts>,
        query: String,
    ): Int {
        var count = 0
        for (message in messages) {
            for (part in message.parts) {
                if (part !is TextPart) continue
                count += countIn(part.text, query)
            }
        }
        return count
    }

    /** Non-overlapping case-insensitive occurrence count of [query] within [text]. */
    private fun countIn(text: String, query: String): Int {
        if (query.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val next = text.indexOf(query, idx, ignoreCase = true)
            if (next < 0) break
            count++
            idx = next + query.length
        }
        return count
    }
}
