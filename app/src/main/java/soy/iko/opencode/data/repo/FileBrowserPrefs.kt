package soy.iko.opencode.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.fileBrowserPrefsDataStore by preferencesDataStore(name = "file_browser_prefs")

/** Sort axis for the file-browser directory listing. Name is always available; Size/Modified
 *  depend on the server emitting size/mtime (otherwise they sort nulls to the bottom). */
enum class FileSortKey { NAME, SIZE, MODIFIED }

/**
 * Persisted file-browser display prefs (folders-first, show-hidden, sort key + direction,
 * changed-only filter) so a user who prefers size-sort or hidden-files-visible keeps it
 * across restarts, not just rotation.
 *
 * `open` with a `protected` no-arg constructor so tests can subclass and override the flows
 * without a real DataStore (mirrors the other stores). The flow accessors are getters (not
 * eager initializers) and null-guard the context so the test constructor never touches disk.
 */
@Suppress("TooManyFunctions")
open class FileBrowserPrefs private constructor(
    private val appContext: Context?,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(context: Context) : this(context.applicationContext, false)
    protected constructor() : this(null, true)

    private val foldersFirstKey = booleanPreferencesKey("folders_first")
    private val showHiddenKey = booleanPreferencesKey("show_hidden")
    private val sortKeyPref = stringPreferencesKey("sort_key")
    private val sortDescKey = booleanPreferencesKey("sort_desc")
    private val changedOnlyKey = booleanPreferencesKey("changed_only")

    // Folders-first is the most useful default; the toggle flips between folders-first name
    // sort and the server's raw order.
    open val foldersFirst: Flow<Boolean>
        get() = appContext?.fileBrowserPrefsDataStore?.data?.map { it[foldersFirstKey] ?: true }
            ?: flowOf(true)

    // Default off so the listing isn't cluttered with dotfiles; the user reveals .env / .git
    // / .opencode on demand.
    open val showHidden: Flow<Boolean>
        get() = appContext?.fileBrowserPrefsDataStore?.data?.map { it[showHiddenKey] ?: false }
            ?: flowOf(false)

    // Stored as the enum name; an unknown value falls back to NAME (forward-compatible with a
    // future rename/removal of an enum entry).
    open val sortKey: Flow<FileSortKey>
        get() = appContext?.fileBrowserPrefsDataStore?.data?.map { prefs ->
            runCatching { FileSortKey.valueOf(prefs[sortKeyPref] ?: FileSortKey.NAME.name) }
                .getOrDefault(FileSortKey.NAME)
        } ?: flowOf(FileSortKey.NAME)

    open val sortDesc: Flow<Boolean>
        get() = appContext?.fileBrowserPrefsDataStore?.data?.map { it[sortDescKey] ?: false }
            ?: flowOf(false)

    // Default off: the changed-only filter narrows the listing to git-tracked changes, which is
    // useful when reviewing a diff but not the default browsing view.
    open val changedOnly: Flow<Boolean>
        get() = appContext?.fileBrowserPrefsDataStore?.data?.map { it[changedOnlyKey] ?: false }
            ?: flowOf(false)

    open suspend fun setFoldersFirst(value: Boolean) {
        appContext?.fileBrowserPrefsDataStore?.edit { it[foldersFirstKey] = value }
    }

    open suspend fun setShowHidden(value: Boolean) {
        appContext?.fileBrowserPrefsDataStore?.edit { it[showHiddenKey] = value }
    }

    open suspend fun setSortKey(key: FileSortKey) {
        appContext?.fileBrowserPrefsDataStore?.edit { it[sortKeyPref] = key.name }
    }

    open suspend fun setSortDesc(value: Boolean) {
        appContext?.fileBrowserPrefsDataStore?.edit { it[sortDescKey] = value }
    }

    open suspend fun setChangedOnly(value: Boolean) {
        appContext?.fileBrowserPrefsDataStore?.edit { it[changedOnlyKey] = value }
    }
}
