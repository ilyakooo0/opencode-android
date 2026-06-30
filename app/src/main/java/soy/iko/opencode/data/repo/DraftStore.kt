package soy.iko.opencode.data.repo

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Persists the in-progress message draft for each session so it survives
 * back-navigation and process death. Backed by SharedPreferences because the
 * UI needs the initial value synchronously and drafts are low-stakes data.
 *
 * The prefs file is loaded on a background thread at construction time. Until
 * the load completes, [drafts] reports an empty map and [get] returns "" rather
 * than blocking the caller (previously a [CountDownLatch] could stall the main
 * thread for up to 2 seconds on a slow disk).
 */
class DraftStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences("drafts", Context.MODE_PRIVATE)
    }

    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The full in-memory snapshot of all drafts, updated when prefs finish loading. */
    val drafts: StateFlow<Map<String, String>> = _drafts.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** True once the background prefs load has completed. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        // Eagerly load the prefs file on a background thread so the first main-thread
        // get() doesn't block on disk I/O. The loaded snapshot is published via [_drafts]
        // so callers can observe readiness without blocking.
        scope.launch(Dispatchers.IO) {
            val snapshot = runCatching {
                prefs.all.mapValues { it.value as? String ?: "" }
            }.getOrDefault(emptyMap())
            _drafts.value = snapshot
            _ready.value = true
        }
    }

    /** Returns the draft for [sessionId], or "" if not found or still loading. */
    fun get(sessionId: String): String = _drafts.value[sessionId].orEmpty()

    suspend fun set(sessionId: String, text: String) {
        _drafts.update { current ->
            val updated = current.toMutableMap()
            if (text.isBlank()) updated.remove(sessionId) else updated[sessionId] = text
            updated
        }
        withContext(Dispatchers.IO) {
            runCatching {
                prefs.edit().apply {
                    if (text.isBlank()) remove(sessionId) else putString(sessionId, text)
                }.apply()
            }.onFailure { Log.w("DraftStore", "Failed to persist draft for $sessionId", it) }
        }
    }

    /** Remove the draft for a session (call on session deletion to avoid orphaned entries). */
    suspend fun remove(sessionId: String) {
        _drafts.update { current ->
            val updated = current.toMutableMap()
            updated.remove(sessionId)
            updated
        }
        withContext(Dispatchers.IO) {
            runCatching {
                prefs.edit().remove(sessionId).apply()
            }.onFailure { Log.w("DraftStore", "Failed to remove draft for $sessionId", it) }
        }
    }

    /**
     * Synchronously persist a draft. Intended for [ViewModel.onCleared] where the
     * viewModelScope is already cancelled and a suspending write can't be used.
     * Uses [apply] (asynchronous) rather than [commit] (synchronous) to avoid
     * blocking the main thread — Android's SharedPreferences framework guarantees
     * pending `apply()` writes are flushed to disk before the process exits.
     */
    fun flushDraft(sessionId: String, text: String) {
        _drafts.update { current ->
            val updated = current.toMutableMap()
            if (text.isBlank()) updated.remove(sessionId) else updated[sessionId] = text
            updated
        }
        runCatching {
            prefs.edit().apply {
                if (text.isBlank()) remove(sessionId) else putString(sessionId, text)
            }.apply()
        }.onFailure { Log.w("DraftStore", "Failed to flush draft for $sessionId", it) }
    }
}
