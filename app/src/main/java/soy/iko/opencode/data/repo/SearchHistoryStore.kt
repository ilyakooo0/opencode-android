package soy.iko.opencode.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import soy.iko.opencode.data.network.NetworkConfig

/**
 * Persists the most recent unique global-search queries so the search screen can
 * surface them as suggestions. Backed by SharedPreferences because the UI needs the
 * initial value synchronously and the list is tiny (≤ [MAX_ENTRIES]), mirroring the
 * low-stakes-persistence rationale of [DraftStore]. `open` with a `protected`
 * no-arg constructor so tests can subclass and override without a real Context.
 */
open class SearchHistoryStore private constructor(
    private val appContext: Context?,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(context: Context) : this(context.applicationContext, false)
    protected constructor() : this(null, true)

    private val prefs by lazy {
        appContext?.getSharedPreferences("search_history", Context.MODE_PRIVATE)
            ?: error("No context — override methods in test subclass")
    }

    private val _queries = MutableStateFlow<List<String>>(emptyList())
    open val queries: StateFlow<List<String>> = _queries.asStateFlow()

    init {
        if (appContext != null) {
            _queries.value = runCatching {
                prefs.getString(KEY, null)
                    ?.takeIf { it.isNotBlank() }
                    ?.split(SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    ?.take(MAX_ENTRIES)
            }.getOrDefault(emptyList()) ?: emptyList()
        }
    }

    /** Insert [query] at the head of the history, deduped and capped at [MAX_ENTRIES].
     *  Blank or sub-min-length queries are ignored so accidental empties and stray
     *  keystrokes don't pollute the suggestions. */
    open fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || trimmed.length < NetworkConfig.minSearchQueryLength) return
        val updated = (listOf(trimmed) + _queries.value.filter { it != trimmed }).take(MAX_ENTRIES)
        _queries.value = updated
        runCatching {
            prefs.edit().putString(KEY, updated.joinToString(SEPARATOR)).apply()
        }
    }

    open fun clear() {
        _queries.value = emptyList()
        runCatching { prefs.edit().remove(KEY).apply() }
    }

    private companion object {
        const val KEY = "queries"
        // The search field is single-line, so a newline can't appear in a stored query
        // and is safe to use as the list separator.
        const val SEPARATOR = "\n"
        const val MAX_ENTRIES = 10
    }
}
