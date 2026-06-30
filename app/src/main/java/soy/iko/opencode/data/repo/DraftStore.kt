package soy.iko.opencode.data.repo

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persists the in-progress message draft for each session so it survives
 * back-navigation and process death. Backed by SharedPreferences because the
 * UI needs the initial value synchronously and drafts are low-stakes data.
 *
 * The prefs file is loaded on a background thread at construction time so the
 * first [get] call on the main thread doesn't trigger a synchronous disk read.
 */
class DraftStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val prefsReady = CountDownLatch(1)
    private val prefs by lazy {
        appContext.getSharedPreferences("drafts", Context.MODE_PRIVATE)
    }

    init {
        // Eagerly load the prefs file on a background thread so the first main-thread
        // get() doesn't block on disk I/O. The latch ensures get() waits for this
        // background load instead of triggering its own lazy load on the main thread.
        scope.launch {
            runCatching { prefs.all }
            prefsReady.countDown()
        }
    }

    fun get(sessionId: String): String {
        prefsReady.await(2, TimeUnit.SECONDS)
        return prefs.getString(sessionId, "").orEmpty()
    }

    fun set(sessionId: String, text: String) {
        prefsReady.await(2, TimeUnit.SECONDS)
        prefs.edit().apply {
            if (text.isBlank()) remove(sessionId) else putString(sessionId, text)
        }.apply()
    }

    /** Remove the draft for a session (call on session deletion to avoid orphaned entries). */
    fun remove(sessionId: String) {
        prefsReady.await(2, TimeUnit.SECONDS)
        prefs.edit().remove(sessionId).apply()
    }
}
