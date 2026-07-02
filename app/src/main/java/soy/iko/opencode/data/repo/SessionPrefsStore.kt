package soy.iko.opencode.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.sessionPrefsDataStore by preferencesDataStore(name = "session_prefs")

/**
 * Local, per-session organization flags the opencode server doesn't track: **pinned**
 * (sorted to the top of the session list) and **archived** (hidden from the list unless the
 * user opts to show them). Keyed by opencode session id.
 *
 * `open` with a `protected` no-arg constructor so tests can subclass and override the flows
 * without a real DataStore (mirrors the other stores). The flow accessors are getters (not
 * eager initializers) and null-guard the context so the test constructor never touches disk.
 */
open class SessionPrefsStore private constructor(
    private val appContext: Context?,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(context: Context) : this(context.applicationContext, false)
    protected constructor() : this(null, true)

    private val pinnedKey = stringSetPreferencesKey("pinned")
    private val archivedKey = stringSetPreferencesKey("archived")

    open val pinned: Flow<Set<String>>
        get() = appContext?.sessionPrefsDataStore?.data?.map { it[pinnedKey] ?: emptySet() }
            ?: flowOf(emptySet())

    open val archived: Flow<Set<String>>
        get() = appContext?.sessionPrefsDataStore?.data?.map { it[archivedKey] ?: emptySet() }
            ?: flowOf(emptySet())

    open suspend fun setPinned(sessionId: String, pinned: Boolean) = toggle(pinnedKey, sessionId, pinned)

    open suspend fun setArchived(sessionId: String, archived: Boolean) = toggle(archivedKey, sessionId, archived)

    /** Drop a session from both sets — call on deletion so ids don't accumulate forever. */
    open suspend fun forget(sessionId: String) {
        val ctx = appContext ?: return
        ctx.sessionPrefsDataStore.edit { prefs ->
            prefs[pinnedKey] = (prefs[pinnedKey] ?: emptySet()) - sessionId
            prefs[archivedKey] = (prefs[archivedKey] ?: emptySet()) - sessionId
        }
    }

    private suspend fun toggle(
        key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>,
        sessionId: String,
        on: Boolean,
    ) {
        val ctx = appContext ?: return
        ctx.sessionPrefsDataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (on) current + sessionId else current - sessionId
        }
    }
}
