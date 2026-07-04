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
    // Every field is defaulted (to SettingsStore's own defaults) so a backup written by an older
    // app version — one whose settings block predates a field added later — still decodes. Without
    // defaults a missing field throws MissingFieldException in import() BEFORE the server-restore
    // loop, aborting the ENTIRE restore (servers/pins/archives included) despite the best-effort
    // contract. ignoreUnknownKeys only tolerates EXTRA keys, not missing required ones.
    val themeMode: String = ThemeMode.SYSTEM.name,
    val dynamicColor: Boolean = true,
    val sendOnEnter: Boolean = true,
    val appLock: Boolean = false,
    val appLockReLockSeconds: Int = SettingsStore.DEFAULT_APP_LOCK_RELOCK_SECONDS,
    val chatTextScale: Float = SettingsStore.DEFAULT_CHAT_TEXT_SCALE,
    val codeWrap: Boolean = false,
    val sessionSortMode: String = "RECENT",
    val sessionSortDescending: Boolean = true,
    val sessionShowArchived: Boolean = false,
    val preferredModelId: String = "",
    val preferredAgentName: String = "",
    val compactMessageSpacing: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val reducedMotion: Boolean = false,
    val languageOverride: String = "",
    val notifRunComplete: Boolean = true,
    val notifPermission: Boolean = true,
    val notifError: Boolean = true,
    val swipeLeftAction: String = SwipeAction.DELETE.name,
    val swipeRightAction: String = SwipeAction.ARCHIVE.name,
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
            appLockReLockSeconds = settingsStore.appLockReLockSeconds.first(),
            chatTextScale = settingsStore.chatTextScale.first(),
            codeWrap = settingsStore.codeWrap.first(),
            sessionSortMode = settingsStore.sessionSortMode.first(),
            sessionSortDescending = settingsStore.sessionSortDescending.first(),
            sessionShowArchived = settingsStore.sessionShowArchived.first(),
            preferredModelId = settingsStore.preferredModelId.first(),
            preferredAgentName = settingsStore.preferredAgentName.first(),
            compactMessageSpacing = settingsStore.compactMessageSpacing.first(),
            hapticsEnabled = settingsStore.hapticsEnabled.first(),
            reducedMotion = settingsStore.reducedMotion.first(),
            languageOverride = settingsStore.languageOverride.first(),
            notifRunComplete = settingsStore.notifRunComplete.first(),
            notifPermission = settingsStore.notifPermission.first(),
            notifError = settingsStore.notifError.first(),
            swipeLeftAction = settingsStore.swipeLeftAction.first(),
            swipeRightAction = settingsStore.swipeRightAction.first(),
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
            settingsStore.setAppLockReLockSeconds(settings.appLockReLockSeconds)
            settingsStore.setChatTextScale(settings.chatTextScale)
            settingsStore.setCodeWrap(settings.codeWrap)
            settingsStore.setSessionSortMode(settings.sessionSortMode)
            settingsStore.setSessionSortDescending(settings.sessionSortDescending)
            settingsStore.setSessionShowArchived(settings.sessionShowArchived)
            settingsStore.setPreferredModelId(settings.preferredModelId)
            settingsStore.setPreferredAgentName(settings.preferredAgentName)
            settingsStore.setCompactMessageSpacing(settings.compactMessageSpacing)
            settingsStore.setHapticsEnabled(settings.hapticsEnabled)
            settingsStore.setReducedMotion(settings.reducedMotion)
            settingsStore.setLanguageOverride(settings.languageOverride)
            settingsStore.setNotifRunComplete(settings.notifRunComplete)
            settingsStore.setNotifPermission(settings.notifPermission)
            settingsStore.setNotifError(settings.notifError)
            SwipeAction.entries.find { it.name == settings.swipeLeftAction }?.let { settingsStore.setSwipeLeftAction(it) }
            SwipeAction.entries.find { it.name == settings.swipeRightAction }?.let { settingsStore.setSwipeRightAction(it) }
        }
        data.pinned.forEach { sessionPrefsStore.setPinned(it, true) }
        data.archived.forEach { sessionPrefsStore.setArchived(it, true) }
    }
}
