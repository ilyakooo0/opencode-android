package soy.iko.opencode.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** App-preferences store: theme mode, dynamic color (Material You), input behavior. */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val themeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val sendOnEnterKey = booleanPreferencesKey("send_on_enter")
    private val appLockKey = booleanPreferencesKey("app_lock")

    val themeMode: Flow<ThemeMode> = appContext.settingsDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    val dynamicColor: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[dynamicColorKey] ?: true
    }

    /** When true, the hardware Enter key sends the prompt; when false, Enter inserts a
     *  newline and Ctrl+Enter sends. Soft-keyboard IME action always sends. Defaults to
     *  true to match the prior hardcoded behavior. */
    val sendOnEnter: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[sendOnEnterKey] ?: true
    }

    /** When true, the app requires device biometric/credential authentication on launch
     *  (and when returning from the background) before the UI is shown, protecting the
     *  stored server credentials. Defaults to false so the app is usable out of the box. */
    val appLock: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[appLockKey] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        appContext.settingsDataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[dynamicColorKey] = enabled }
    }

    suspend fun setSendOnEnter(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[sendOnEnterKey] = enabled }
    }

    suspend fun setAppLock(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[appLockKey] = enabled }
    }
}
