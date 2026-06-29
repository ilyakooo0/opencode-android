package soy.iko.opencode.data.repo

import android.content.Context

/**
 * Persists the in-progress message draft for each session so it survives
 * back-navigation and process death. Backed by SharedPreferences because the
 * UI needs the initial value synchronously and drafts are low-stakes data.
 */
class DraftStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("drafts", Context.MODE_PRIVATE)

    fun get(sessionId: String): String = prefs.getString(sessionId, "").orEmpty()

    fun set(sessionId: String, text: String) {
        prefs.edit().apply {
            if (text.isBlank()) remove(sessionId) else putString(sessionId, text)
        }.apply()
    }
}
