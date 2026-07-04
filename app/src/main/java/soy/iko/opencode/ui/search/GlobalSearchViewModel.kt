package soy.iko.opencode.ui.search

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.R
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ReasoningPart
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.ToolCompleted
import soy.iko.opencode.data.model.ToolError
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.ToolRunning
import soy.iko.opencode.data.model.inputElement
import soy.iko.opencode.data.model.sourcePath
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
    /** True when there were more sessions than the current cap, so the search only covered
     *  the most recent ones. The cap starts at [NetworkConfig.maxSearchSessions] and grows
     *  via [searchMore] so the user can page through older sessions. */
    val truncated: Boolean = false,
    /** How many sessions have been scanned so far in the current pass, for progress feedback
     *  (a global search downloads each session's history, so a 50-session pass can take a
     *  while; surfacing the count keeps the spinner from looking stuck). */
    val searchedCount: Int = 0,
    /** Total sessions being scanned in the current pass ([searchedCount] / [totalCount]). */
    val totalCount: Int = 0,
    /** The cap applied to this pass. Grows when the user taps "Search more". */
    val sessionCap: Int = NetworkConfig.maxSearchSessions,
    /** Message-type filter; ALL matches everything, the others restrict which parts are
     *  searchable so a user can scope "just tool calls" vs "just reasoning". */
    val typeFilter: SearchTypeFilter = SearchTypeFilter.ALL,
    /** When true the query matches case-sensitively (the default is case-insensitive). */
    val matchCase: Boolean = false,
    /** Persisted recent queries shown as suggestions when the search field is empty. */
    val history: List<String> = emptyList(),
)

/** Scopes a global search to a category of message part. */
enum class SearchTypeFilter { ALL, MESSAGES, TOOLS, REASONING }

/**
 * Cross-session message search. opencode's `/find` searches project *files*, not chat
 * history, so message search is done client-side: fetch each session's messages (bounded and
 * concurrent) and match their text. Runs only on an explicit, debounced query since it
 * downloads history.
 */
class GlobalSearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        GlobalSearchState(history = container.searchHistoryStore.queries.value),
    )
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
            // Record the query only after a search actually ran (not on every keystroke that
            // crossed the min-length threshold), so history reflects real searches.
            if (_state.value.query.trim() == trimmed) {
                container.searchHistoryStore.add(trimmed)
                _state.update { it.copy(history = container.searchHistoryStore.queries.value) }
            }
        }
    }

    /** Swap the message-type filter and re-run the current query (no debounce) so the chip
     *  change is reflected immediately. The message cache is preserved. */
    fun setTypeFilter(filter: SearchTypeFilter) {
        if (_state.value.typeFilter == filter) return
        _state.update { it.copy(typeFilter = filter) }
        relaunchCurrent()
    }

    /** Toggle case-sensitivity and re-run the current query (no debounce). */
    fun setMatchCase(matchCase: Boolean) {
        if (_state.value.matchCase == matchCase) return
        _state.update { it.copy(matchCase = matchCase) }
        relaunchCurrent()
    }

    /** Drop all persisted search history (the "Clear history" action). */
    fun clearHistory() {
        container.searchHistoryStore.clear()
        _state.update { it.copy(history = emptyList()) }
    }

    private fun relaunchCurrent() {
        val trimmed = _state.value.query.trim()
        if (trimmed.length < NetworkConfig.minSearchQueryLength) return
        searchJob?.cancel()
        _state.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch { runSearch(trimmed) }
    }

    /** Re-run the current query immediately (no debounce), used by the error-state Retry. Clears
     *  the caches so the retry actually refetches (the prior attempt may have failed). */
    fun retry() {
        val trimmed = _state.value.query.trim()
        if (trimmed.length < NetworkConfig.minSearchQueryLength) return
        sessionsCache = null
        messageCache.clear()
        searchJob?.cancel()
        _state.update { it.copy(searching = true, error = null, sessionCap = NetworkConfig.maxSearchSessions) }
        searchJob = viewModelScope.launch { runSearch(trimmed) }
    }

    /** Expand the search to cover more sessions (the next [NetworkConfig.maxSearchSessions] batch)
     *  and re-run. No-op when the prior pass wasn't truncated (there's nothing more to search).
     *  The message cache is preserved so already-downloaded sessions aren't re-fetched. */
    fun searchMore() {
        if (!_state.value.truncated) return
        val trimmed = _state.value.query.trim()
        if (trimmed.length < NetworkConfig.minSearchQueryLength) return
        val newCap = _state.value.sessionCap + NetworkConfig.maxSearchSessions
        searchJob?.cancel()
        _state.update { it.copy(searching = true, error = null, sessionCap = newCap) }
        searchJob = viewModelScope.launch { runSearch(trimmed, newCap) }
    }

    private suspend fun runSearch(query: String, cap: Int = _state.value.sessionCap) {
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
        val toSearch = sessions.take(cap)
        val truncated = sessions.size > toSearch.size
        _state.update { it.copy(totalCount = toSearch.size, searchedCount = 0, truncated = truncated) }
        val hits = java.util.Collections.synchronizedMap(HashMap<String, SearchHit>())
        val semaphore = Semaphore(NetworkConfig.maxConcurrentPreviews)
        val searched = java.util.concurrent.atomic.AtomicInteger(0)
        val ignoreCase = !_state.value.matchCase
        val filter = _state.value.typeFilter
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
                        val snippet = matchSnippet(messages, query, ignoreCase, filter)
                        // The session title isn't a message part, so the type filter doesn't
                        // apply to it — a title match is always surfaced.
                        val titleMatched = session.displayTitle.contains(query, ignoreCase = ignoreCase)
                        if (snippet != null || titleMatched) {
                            val count = countMatches(messages, query, ignoreCase, filter) +
                                countIn(session.displayTitle, query, ignoreCase)
                            hits[session.id] = SearchHit(
                                session = session,
                                snippet = snippet
                                    ?: session.displayTitle.takeIf { titleMatched }.orEmpty(),
                                matchCount = count.coerceAtLeast(1),
                                matchedTitle = titleMatched,
                            )
                        }
                        // Publish progress so the spinner can show "Searched N / M sessions".
                        _state.update { it.copy(searchedCount = searched.incrementAndGet()) }
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

    /** Find the first searchable text containing [query] across [messages] and return a short
     *  snippet centered on the match, or null if nothing matches. [ignoreCase] controls
     *  case-sensitivity; [filter] restricts which part types contribute searchable text. */
    private fun matchSnippet(
        messages: List<soy.iko.opencode.data.model.MessageWithParts>,
        query: String,
        ignoreCase: Boolean,
        filter: SearchTypeFilter = SearchTypeFilter.ALL,
    ): String? {
        for (message in messages) {
            for (candidate in searchableTexts(message.parts, filter)) {
                val idx = candidate.indexOf(query, ignoreCase = ignoreCase)
                if (idx >= 0) return buildSnippet(candidate, idx, query.length)
            }
        }
        return null
    }

    /** The ordered list of searchable strings for a message's parts, scoped by [filter].
     *  Text and reasoning are searched in full; tool calls contribute their name, title,
     *  output, error, and the stringified input; file parts contribute their filename and
     *  source path. A non-ALL filter narrows to only the matching part type, so "Tool calls"
     *  excludes text/reasoning/file matches. */
    private fun searchableTexts(
        parts: List<soy.iko.opencode.data.model.Part>,
        filter: SearchTypeFilter = SearchTypeFilter.ALL,
    ): List<String> {
        if (parts.isEmpty()) return emptyList()
        val out = ArrayList<String>(parts.size)
        for (p in parts) {
            when (p) {
                is TextPart -> if (filter == SearchTypeFilter.ALL || filter == SearchTypeFilter.MESSAGES) out.add(p.text)
                is ReasoningPart -> if (filter == SearchTypeFilter.ALL || filter == SearchTypeFilter.REASONING) out.add(p.text)
                is ToolPart -> if (filter == SearchTypeFilter.ALL || filter == SearchTypeFilter.TOOLS) {
                    out.add(p.tool)
                    when (p.state) {
                        is ToolRunning -> p.state.title?.let(out::add)
                        is ToolCompleted -> {
                            p.state.title?.let(out::add)
                            p.state.output?.let(out::add)
                        }
                        is ToolError -> p.state.error?.let(out::add)
                        else -> {}
                    }
                    p.state.inputElement()?.toString()?.let(out::add)
                }
                is FilePart -> if (filter == SearchTypeFilter.ALL) {
                    p.filename?.let(out::add)
                    p.sourcePath?.let(out::add)
                }
                else -> {}
            }
        }
        return out
    }

    private fun buildSnippet(text: String, matchStart: Int, matchLength: Int): String {
        val window = NetworkConfig.searchSnippetLength
        val start = (matchStart - window / 2).coerceAtLeast(0)
        val end = (matchStart + matchLength + window / 2).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    /** Count all occurrences of [query] across the searchable text of [messages] (text,
     *  reasoning, tool calls, file names) so a result card can show "N matches". */
    private fun countMatches(
        messages: List<soy.iko.opencode.data.model.MessageWithParts>,
        query: String,
        ignoreCase: Boolean,
        filter: SearchTypeFilter = SearchTypeFilter.ALL,
    ): Int {
        var count = 0
        for (message in messages) {
            for (text in searchableTexts(message.parts, filter)) {
                count += countIn(text, query, ignoreCase)
            }
        }
        return count
    }

    /** Non-overlapping occurrence count of [query] within [text], case-insensitive unless
     *  [ignoreCase] is false. */
    private fun countIn(text: String, query: String, ignoreCase: Boolean): Int {
        if (query.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val next = text.indexOf(query, idx, ignoreCase = ignoreCase)
            if (next < 0) break
            count++
            idx = next + query.length
        }
        return count
    }
}
