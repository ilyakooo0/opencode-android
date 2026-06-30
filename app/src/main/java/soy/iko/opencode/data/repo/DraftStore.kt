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
import kotlinx.coroutines.runBlocking
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
        prefsInitialized.set(true)
        appContext.getSharedPreferences("drafts", Context.MODE_PRIVATE)
    }

    /** Tracks whether the [prefs] lazy has been resolved (i.e. the file has been
     *  opened from disk). Accessed from [flushDraft] to decide whether a synchronous
     *  write on the main thread is safe or whether the write must be deferred to a
     *  background thread to avoid blocking on first-time disk I/O. */
    private val prefsInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

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
     *
     * If the SharedPreferences file has already been opened (the normal case — the
     * background init load touches it shortly after construction), the write uses
     * [apply] (asynchronous) and returns immediately without blocking the main thread.
     *
     * If the prefs file has NOT been opened yet (a rare race where onCleared fires
     * before the background init), accessing the lazy would synchronously load and
     * parse the XML on the main thread. To avoid this, the write is deferred to a
     * blocking call on the IO dispatcher instead.
     */
    fun flushDraft(sessionId: String, text: String) {
        _drafts.update { current ->
            val updated = current.toMutableMap()
            if (text.isBlank()) updated.remove(sessionId) else updated[sessionId] = text
            updated
        }
        if (prefsInitialized.get()) {
            // Prefs already opened — the lazy resolves instantly and apply() is async.
            runCatching {
                prefs.edit().apply {
                    if (text.isBlank()) remove(sessionId) else putString(sessionId, text)
                }.apply()
            }.onFailure { Log.w("DraftStore", "Failed to flush draft for $sessionId", it) }
        } else {
            // Prefs not yet opened — avoid a synchronous disk read on the main thread
            // by blocking on IO instead. This is acceptable in onCleared() because the
            // viewModelScope is already dead and there's no suspending alternative.
            runCatching {
                runBlocking(Dispatchers.IO) {
                    prefs.edit().apply {
                        if (text.isBlank()) remove(sessionId) else putString(sessionId, text)
                    }.apply()
                }
            }.onFailure { Log.w("DraftStore", "Failed to flush draft for $sessionId", it) }
        }
    }
}
