package soy.iko.opencode.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    private val chatTextScaleKey = floatPreferencesKey("chat_text_scale")
    private val codeWrapKey = booleanPreferencesKey("code_wrap")

    companion object {
        /** Bounds for the chat text-size multiplier so the UI can't be scaled into
         *  unreadability or an unusable micro-font. 1.0 is the design default. */
        const val MIN_CHAT_TEXT_SCALE = 0.8f
        const val MAX_CHAT_TEXT_SCALE = 1.6f
        const val DEFAULT_CHAT_TEXT_SCALE = 1.0f
    }

    val themeMode: Flow<ThemeMode> = appContext.settingsDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    // Default OFF so the app ships with its hand-built "Tokyo Night" brand palette rather than
    // the wallpaper-derived Material You scheme, which fully replaces the brand colours. Users
    // on Android 12+ can still opt into dynamic color from Settings.
    val dynamicColor: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[dynamicColorKey] ?: false
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

    /** Multiplier applied to chat message (markdown) and code text so users can size the
     *  conversation for readability independently of the system font scale. Clamped to
     *  [[MIN_CHAT_TEXT_SCALE], [MAX_CHAT_TEXT_SCALE]] on read so a corrupt/out-of-range
     *  stored value can never produce an unreadable UI. Defaults to 1.0 (design size). */
    val chatTextScale: Flow<Float> = appContext.settingsDataStore.data.map { prefs ->
        (prefs[chatTextScaleKey] ?: DEFAULT_CHAT_TEXT_SCALE)
            .coerceIn(MIN_CHAT_TEXT_SCALE, MAX_CHAT_TEXT_SCALE)
    }

    /** When true, fenced/indented code blocks soft-wrap long lines instead of scrolling
     *  horizontally. Defaults to false (horizontal scroll) to preserve code formatting. */
    val codeWrap: Flow<Boolean> = appContext.settingsDataStore.data.map { prefs ->
        prefs[codeWrapKey] ?: false
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

    suspend fun setChatTextScale(scale: Float) {
        appContext.settingsDataStore.edit {
            it[chatTextScaleKey] = scale.coerceIn(MIN_CHAT_TEXT_SCALE, MAX_CHAT_TEXT_SCALE)
        }
    }

    suspend fun setCodeWrap(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[codeWrapKey] = enabled }
    }
}
