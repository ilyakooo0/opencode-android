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
import java.util.concurrent.Executors

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
        val sp = appContext.getSharedPreferences("drafts", Context.MODE_PRIVATE)
        prefsInitialized.set(true)
        sp
    }

    /** Tracks whether the [prefs] lazy has been resolved (i.e. the file has been
     *  opened from disk). Accessed from [flushDraft] to decide whether a synchronous
     *  write on the main thread is safe or whether the write must be deferred to a
     *  background thread to avoid blocking on first-time disk I/O. */
    private val prefsInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Single-thread executor for flushDraft when prefs aren't initialized yet,
     *  so the main thread is never blocked on disk I/O. */
    private val flushExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DraftStore-flush").apply { isDaemon = true }
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

    /** Update the in-memory draft immediately without persisting to disk.
     *  Used when navigation must see the new value synchronously (e.g. share-intent
     *  injection before ChatScreen composes). Call [set] afterwards to persist. */
    fun setImmediate(sessionId: String, text: String) {
        _drafts.update { current ->
            val updated = current.toMutableMap()
            if (text.isBlank()) updated.remove(sessionId) else updated[sessionId] = text
            updated
        }
    }

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
     * before the background init), the write is dispatched to a background thread
     * instead of blocking the main thread on a synchronous disk read. The draft
     * may be lost if the process exits before the write completes, but this is
     * preferable to an ANR.
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
            // Prefs not yet opened — dispatch to a background thread to avoid a
            // synchronous disk read on the main thread (which would ANR). The draft
            // may be lost if the process exits before the write completes.
            flushExecutor.execute {
                runCatching {
                    prefs.edit().apply {
                        if (text.isBlank()) remove(sessionId) else putString(sessionId, text)
                    }.apply()
                }.onFailure { Log.w("DraftStore", "Failed to flush draft for $sessionId", it) }
            }
        }
    }

    /** Shut down the background flush executor. Call from AppContainer.shutdown(). */
    fun shutdown() {
        flushExecutor.shutdown()
    }
}
