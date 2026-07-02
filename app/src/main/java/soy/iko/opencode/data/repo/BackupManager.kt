package soy.iko.opencode.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import soy.iko.opencode.data.model.ServerProfile

/** A server entry in a backup. [password] is present only when the user opted to include it. */
@Serializable
data class BackupServer(
    val id: String,
    val label: String,
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
    val requireHttps: Boolean = false,
    val certPin: String? = null,
)

/** App preferences in a backup. */
@Serializable
data class BackupSettings(
    val themeMode: String,
    val dynamicColor: Boolean,
    val sendOnEnter: Boolean,
    val appLock: Boolean,
    val chatTextScale: Float,
    val codeWrap: Boolean,
)

/** The full backup document. [version] guards against future format changes. */
@Serializable
data class BackupData(
    val version: Int = 1,
    val servers: List<BackupServer> = emptyList(),
    val settings: BackupSettings? = null,
    val pinned: List<String> = emptyList(),
    val archived: List<String> = emptyList(),
)

/**
 * Exports/imports the app's servers, settings, and per-session flags as a single JSON
 * document (via the Storage Access Framework in the UI). Passwords are only written when the
 * user explicitly opts in, since the exported file is plaintext and leaves the app's encrypted
 * store.
 */
class BackupManager(
    private val profileStore: ProfileStore,
    private val settingsStore: SettingsStore,
    private val sessionPrefsStore: SessionPrefsStore,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** Build the backup JSON. Reads the current profiles/settings/flags. */
    suspend fun export(includePasswords: Boolean): String {
        val servers = profileStore.profiles.first().map {
            BackupServer(
                id = it.id,
                label = it.label,
                baseUrl = it.baseUrl,
                username = it.username,
                password = if (includePasswords) it.password else null,
                requireHttps = it.requireHttps,
                certPin = it.certPin,
            )
        }
        val settings = BackupSettings(
            themeMode = settingsStore.themeMode.first().name,
            dynamicColor = settingsStore.dynamicColor.first(),
            sendOnEnter = settingsStore.sendOnEnter.first(),
            appLock = settingsStore.appLock.first(),
            chatTextScale = settingsStore.chatTextScale.first(),
            codeWrap = settingsStore.codeWrap.first(),
        )
        val data = BackupData(
            servers = servers,
            settings = settings,
            pinned = sessionPrefsStore.pinned.first().toList(),
            archived = sessionPrefsStore.archived.first().toList(),
        )
        return json.encodeToString(data)
    }

    /** Apply a backup document. Throws on malformed JSON so the caller can report it. Servers
     *  are upserted (existing ids overwritten); settings and flags are restored best-effort. */
    suspend fun import(text: String) {
        val data = json.decodeFromString<BackupData>(text)
        for (s in data.servers) {
            val username = s.username?.takeIf { it.isNotBlank() }
            val incomingPassword = s.password?.takeIf { it.isNotEmpty() }
            // A backup exported *without* passwords carries null. This is an upsert, so don't let
            // that null wipe a password already stored for this server id — preserve the existing
            // secret when the backup didn't include one (only the backup explicitly carrying a
            // password should overwrite it). Re-importing a password-less backup would otherwise
            // silently break auth for every matching server.
            val password = incomingPassword ?: username?.let {
                profileStore.resolve(
                    ServerProfile(id = s.id, label = s.label, baseUrl = s.baseUrl, username = it),
                ).password
            }
            profileStore.save(
                ServerProfile(
                    id = s.id,
                    label = s.label,
                    baseUrl = s.baseUrl,
                    username = username,
                    password = password,
                    lastUsed = 0,
                    requireHttps = s.requireHttps,
                    certPin = s.certPin?.takeIf { it.isNotBlank() },
                ),
            )
        }
        data.settings?.let { settings ->
            // Parse the enum defensively (a bad name from an edited backup shouldn't abort the
            // import) WITHOUT wrapping the suspend setThemeMode call — wrapping it would swallow
            // a CancellationException and break structured concurrency.
            ThemeMode.entries.find { it.name == settings.themeMode }?.let { settingsStore.setThemeMode(it) }
            settingsStore.setDynamicColor(settings.dynamicColor)
            settingsStore.setSendOnEnter(settings.sendOnEnter)
            settingsStore.setAppLock(settings.appLock)
            settingsStore.setChatTextScale(settings.chatTextScale)
            settingsStore.setCodeWrap(settings.codeWrap)
        }
        data.pinned.forEach { sessionPrefsStore.setPinned(it, true) }
        data.archived.forEach { sessionPrefsStore.setArchived(it, true) }
    }
}
