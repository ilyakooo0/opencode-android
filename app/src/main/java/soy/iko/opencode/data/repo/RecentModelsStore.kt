package soy.iko.opencode.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the most recently picked models so the model picker can surface them as a
 * "Recent" section above the full catalog. Backed by SharedPreferences because the UI
 * needs the initial value synchronously and the list is tiny (≤ [MAX_ENTRIES]), mirroring
 * the low-stakes-persistence rationale of [SearchHistoryStore]. `open` with a `protected`
 * no-arg constructor so tests can subclass and override without a real Context.
 *
 * Entries are stored as `providerID\modelID` composite keys (the separator is chosen so it
 * can't collide with provider or model ids, which are URL/path-safe identifiers).
 */
open class RecentModelsStore private constructor(
    private val appContext: Context?,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(context: Context) : this(context.applicationContext, false)
    protected constructor() : this(null, true)

    private val prefs by lazy {
        appContext?.getSharedPreferences("recent_models", Context.MODE_PRIVATE)
            ?: error("No context — override methods in test subclass")
    }

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    open val entries: StateFlow<List<String>> = _entries.asStateFlow()

    init {
        if (appContext != null) {
            _entries.value = runCatching {
                prefs.getString(KEY, null)
                    ?.takeIf { it.isNotBlank() }
                    ?.split(SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    ?.take(MAX_ENTRIES)
            }.getOrDefault(emptyList()) ?: emptyList()
        }
    }

    /** Insert [providerId]/[modelId] at the head of the recents, deduped and capped at
     *  [MAX_ENTRIES]. Blank ids are ignored. */
    open fun add(providerId: String, modelId: String) {
        val p = providerId.trim()
        val m = modelId.trim()
        if (p.isBlank() || m.isBlank()) return
        val key = "$p$SEPARATOR$m"
        val updated = (listOf(key) + _entries.value.filter { it != key }).take(MAX_ENTRIES)
        _entries.value = updated
        runCatching {
            prefs.edit().putString(KEY, updated.joinToString(SEPARATOR)).apply()
        }
    }

    /** Split a stored composite key back into its provider/model id pair. Returns null if
     *  the entry is malformed (shouldn't happen, but a corrupt prefs value must never crash
     *  the picker). */
    fun split(entry: String): Pair<String, String>? {
        val parts = entry.split(SEPARATOR)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
            parts[0] to parts[1] else null
    }

    open fun clear() {
        _entries.value = emptyList()
        runCatching { prefs.edit().remove(KEY).apply() }
    }

    private companion object {
        const val KEY = "models"
        // A newline can't appear in a provider/model id (they're URL/path-safe), so it's a
        // safe list separator that won't collide with id contents.
        const val SEPARATOR = "\n"
        const val MAX_ENTRIES = 5
    }
}
