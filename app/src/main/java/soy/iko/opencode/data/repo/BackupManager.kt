package soy.iko.opencode.data.repo

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import soy.iko.opencode.data.model.ServerProfile
import soy.iko.opencode.util.runCatchingCancellable

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
    val dynamicColor: Boolean = false,
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

/** The full backup document. [version] guards against future format changes: [import] rejects
 *  an unknown major version before any writes so a newer-schema backup can't silently
 *  mis-import or leave a partial restore. */
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

    /** Backup format versions [import] knows how to restore. Bump when the schema changes
     *  incompatibly, and add the new value here once the import path handles it. An unknown
     *  version is rejected before any writes so a newer-schema backup can't silently
     *  mis-import or leave a partial restore (servers upserted, then a missing required field
     *  throws mid-settings). */
    private val supportedVersions = setOf(1)

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
     *  are upserted (existing ids overwritten); settings and flags are restored best-effort.
     *  An unknown [BackupData.version] is rejected BEFORE any writes so a newer-schema backup
     *  can't silently mis-import or leave a partial restore (servers upserted, then a missing
     *  required field throws mid-settings). */
    suspend fun import(text: String) {
        val data = json.decodeFromString<BackupData>(text)
        require(data.version in supportedVersions) {
            "Unsupported backup version ${data.version}; supported: $supportedVersions"
        }
        var hadError = false
        for (s in data.servers) {
            runCatchingCancellable {
                val username = s.username?.takeIf { it.isNotBlank() }
                val incomingPassword = s.password?.takeIf { it.isNotEmpty() }
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
            }.onFailure { hadError = true }
        }
        data.settings?.let { settings ->
            runCatchingCancellable {
                ThemeMode.entries.find { it.name == settings.themeMode }?.let { settingsStore.setThemeMode(it) }
                settingsStore.setDynamicColor(settings.dynamicColor)
                settingsStore.setSendOnEnter(settings.sendOnEnter)
                settingsStore.setAppLock(settings.appLock)
                settingsStore.setAppLockReLockSeconds(settings.appLockReLockSeconds)
                settingsStore.setChatTextScale(settings.chatTextScale)
                settingsStore.setCodeWrap(settings.codeWrap)
                settingsStore.setSessionSortDescending(settings.sessionSortDescending)
                settingsStore.setSessionShowArchived(settings.sessionShowArchived)
                settingsStore.setPreferredModelId(settings.preferredModelId)
                settingsStore.setPreferredAgentName(settings.preferredAgentName)
                settingsStore.setCompactMessageSpacing(settings.compactMessageSpacing)
                settingsStore.setHapticsEnabled(settings.hapticsEnabled)
                settingsStore.setReducedMotion(settings.reducedMotion)
                soy.iko.opencode.ui.session.SessionSortMode.entries
                    .find { it.name == settings.sessionSortMode }
                    ?.let { settingsStore.setSessionSortMode(it.name) }
                val lang = settings.languageOverride
                if (lang.isEmpty() || lang.matches(Regex("^[a-z]{2,3}(-[A-Za-z0-9]+)*$"))) {
                    settingsStore.setLanguageOverride(lang)
                }
                settingsStore.setNotifRunComplete(settings.notifRunComplete)
                settingsStore.setNotifPermission(settings.notifPermission)
                settingsStore.setNotifError(settings.notifError)
                SwipeAction.entries.find { it.name == settings.swipeLeftAction }?.let { settingsStore.setSwipeLeftAction(it) }
                SwipeAction.entries.find { it.name == settings.swipeRightAction }?.let { settingsStore.setSwipeRightAction(it) }
            }.onFailure { hadError = true }
        }
        runCatchingCancellable {
            data.pinned.forEach { sessionPrefsStore.setPinned(it, true) }
            data.archived.forEach { sessionPrefsStore.setArchived(it, true) }
        }.onFailure { hadError = true }
        if (hadError) throw IllegalStateException("Backup import completed with errors")
    }
}
