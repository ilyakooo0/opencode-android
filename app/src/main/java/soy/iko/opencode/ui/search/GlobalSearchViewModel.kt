package soy.iko.opencode.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import soy.iko.opencode.R
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer
import soy.iko.opencode.util.runCatchingCancellable
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

/** One search result: the [session] that matched and a [snippet] of the matching text. */
@Immutable
data class SearchHit(val session: Session, val snippet: String)

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

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < NetworkConfig.minSearchQueryLength) {
            _state.update { it.copy(searching = false, results = emptyList(), hasSearched = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(NetworkConfig.searchDebounceMs)
            runSearch(trimmed)
        }
    }

    private suspend fun runSearch(query: String) {
        val conn = container.activeConnection.value
        if (conn == null) {
            _state.update { it.copy(searching = false, error = container.string(R.string.not_connected)) }
            return
        }
        _state.update { it.copy(searching = true, error = null) }
        val sessions = runCatchingCancellable { conn.repository.listSessions() }.getOrElse {
            _state.update { s -> s.copy(searching = false, error = container.friendlyError(it)) }
            return
        }
        val toSearch = sessions.take(NetworkConfig.maxSearchSessions)
        val truncated = sessions.size > toSearch.size
        val hits = java.util.Collections.synchronizedMap(HashMap<String, SearchHit>())
        val semaphore = Semaphore(NetworkConfig.maxConcurrentPreviews)
        coroutineScope {
            toSearch.forEach { session ->
                launch {
                    semaphore.withPermit {
                        val messages = runCatchingCancellable { conn.api.listMessages(session.id) }
                            .getOrDefault(emptyList())
                        val snippet = matchSnippet(messages, query)
                            ?: session.displayTitle.takeIf { it.contains(query, ignoreCase = true) }
                        if (snippet != null) hits[session.id] = SearchHit(session, snippet)
                    }
                }
            }
        }
        // Preserve the session order (already recency-sorted by the server) in the results.
        val ordered = toSearch.mapNotNull { hits[it.id] }
        _state.update {
            it.copy(searching = false, results = ordered, hasSearched = true, truncated = truncated)
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
}
