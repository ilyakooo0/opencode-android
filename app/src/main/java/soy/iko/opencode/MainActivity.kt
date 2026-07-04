package soy.iko.opencode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.content.IntentCompat
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.notification.NotificationActionReceiver
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.combine
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import soy.iko.opencode.data.repo.SettingsStore
import soy.iko.opencode.data.repo.ThemeMode
import soy.iko.opencode.ui.AppLockGate
import soy.iko.opencode.ui.OpencodeApp as OpencodeAppUi
import soy.iko.opencode.ui.theme.OpencodeTheme

// FragmentActivity (not ComponentActivity) so androidx.biometric BiometricPrompt can attach
// for the app-lock gate; FragmentActivity extends ComponentActivity, so Compose/edge-to-edge
// /activity-result APIs are unaffected.
class MainActivity : FragmentActivity() {

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Result is best-effort; notifications are silently skipped if denied. */ }

    private var shareIntentHandled = false
    private var openSessionHandled = false
    private var newSessionHandled = false
    private var openFileHandled = false
    // Cold-start prompts (crash report, notification rationale) rendered as Compose M3
    // dialogs. Hoisted as Activity-level mutableState so the non-composable trigger logic
    // (onCreate/onResume) can flip them and the Compose tree (inside OpencodeTheme) reads
    // them and renders an M3 AlertDialog that picks up the app palette. The triggers
    // themselves are idempotent per instance (crashes are acknowledged on first fire;
    // notificationPermissionRequested is restored from saved state), so a plain
    // mutableStateOf (not saveable) preserves the original dismissal semantics across
    // config-change recreation: the prompt simply doesn't re-fire after a rotate.
    private var showCrashPrompt by mutableStateOf(false)
    private var showNotifRationale by mutableStateOf(false)
    // Track whether the notification-permission prompt has been shown for this Activity
    // instance. onCreate only requests when savedInstanceState == null (a fresh process),
    // but an Activity recreated by a config change NOT listed in the manifest's configChanges
    // (e.g. a locale change) has a non-null savedInstanceState and would otherwise skip the
    // prompt forever. onResume re-requests once per instance when still ungranted so the user
    // is never silently left without notifications after such a recreation.
    private var notificationPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.let {
            shareIntentHandled = it.getBoolean(KEY_SHARE_HANDLED, false)
            openSessionHandled = it.getBoolean(KEY_OPEN_SESSION_HANDLED, false)
            newSessionHandled = it.getBoolean(KEY_NEW_SESSION_HANDLED, false)
            openFileHandled = it.getBoolean(KEY_OPEN_FILE_HANDLED, false)
        }
        val container = (application as OpencodeApp).container
        handleIntent(intent)
        if (savedInstanceState == null) {
            maybeRequestNotificationPermission()
        } else {
            // Restored instance: the permission may have been requested pre-recreation, but
            // we can't know the result from here. onResume will re-check and request if still
            // ungranted (once per Activity instance).
            notificationPermissionRequested = savedInstanceState.getBoolean(KEY_NOTIF_PERM_REQUESTED, false)
        }
        // Hold the splash until the persisted theme and app-lock settings load, so we never
        // paint a frame with the defaults (dynamicColor=false / SYSTEM, app lock off) that then
        // snap to the user's real choice — including a brief unlocked, un-FLAG_SECURE'd frame
        // before app lock resolves. Mirrors the SettingsScreen's null-gate.
        var settingsLoaded = false
        splash.setKeepOnScreenCondition { !settingsLoaded }
        setContent {
            val theme by remember(container) {
                container.settingsStore.themeMode
                    .combine(container.settingsStore.dynamicColor, ::Pair)
            }.collectAsStateWithLifecycle(initialValue = null)
            val appLock by container.settingsStore.appLock.collectAsStateWithLifecycle(initialValue = null)
            val appLockReLockSeconds by container.settingsStore.appLockReLockSeconds
                .collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_APP_LOCK_RELOCK_SECONDS)
            LaunchedEffect(theme, appLock) { if (theme != null && appLock != null) settingsLoaded = true }
            // Hide the app's content from screenshots / the recents thumbnail while app lock is
            // enabled — and while its value is still unknown (null) at cold start, so a protected
            // launch can't flash into the recents thumbnail before the setting resolves.
            LaunchedEffect(appLock) {
                if (appLock != false) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val themePair = theme
            val locked = appLock
            if (themePair != null && locked != null) {
                val (themeMode, dynamicColor) = themePair
                val amoled = themeMode == ThemeMode.AMOLED
                val dark = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.AMOLED -> true
                }
                OpencodeTheme(darkTheme = dark, dynamicColor = dynamicColor, amoled = amoled) {
                    AppLockGate(enabled = locked, reLockDelaySeconds = appLockReLockSeconds) {
                        OpencodeAppUi(container = container)
                    }
                    // First-launch prompts as Compose M3 dialogs (siblings of AppLockGate so they
                    // still surface over the lock screen, matching the prior framework-dialog
                    // behavior) and inside OpencodeTheme so they use the app palette.
                    if (showCrashPrompt) {
                        AlertDialog(
                            onDismissRequest = { showCrashPrompt = false },
                            title = { Text(stringResource(R.string.crash_last_run_title)) },
                            text = { Text(stringResource(R.string.crash_last_run_text)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showCrashPrompt = false
                                    container.requestDiagnostics()
                                }) { Text(stringResource(R.string.crash_last_run_view)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCrashPrompt = false }) {
                                    Text(stringResource(R.string.crash_last_run_dismiss))
                                }
                            },
                        )
                    }
                    if (showNotifRationale) {
                        AlertDialog(
                            // Mirrors the original setCancelable(false): not dismissible by
                            // back/outside tap, only by the buttons below.
                            onDismissRequest = { },
                            title = { Text(stringResource(R.string.notif_rationale_title)) },
                            text = { Text(stringResource(R.string.notif_rationale_text)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showNotifRationale = false
                                    runCatching { requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                }) { Text(stringResource(R.string.notif_rationale_allow)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNotifRationale = false }) {
                                    Text(stringResource(R.string.notif_rationale_skip))
                                }
                            },
                        )
                    }
                }
            }
        }
        // Surface a "crashed last time" prompt if a crash report from a previous run
        // hasn't been acknowledged. Acknowledges immediately so it doesn't re-fire on
        // every rotation/resume; the user can always find reports in Settings → Diagnostics.
        maybeShowCrashPrompt()
    }

    private fun maybeShowCrashPrompt() {
        val crashLogger = soy.iko.opencode.data.repo.CrashLogger.get(this)
        if (!crashLogger.hasUnacknowledgedCrash()) return
        crashLogger.acknowledgeCrashes()
        showCrashPrompt = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareIntentHandled = false
        openSessionHandled = false
        newSessionHandled = false
        openFileHandled = false
        handleIntent(intent)
    }

    // Track real foreground state so background notifications (permission requests,
    // completions, errors) still fire for a session the user "has open" but has walked
    // away from — the chat screen isn't disposed when the phone is locked, so
    // container.currentSession stays set and can't be used as a foreground signal.
    override fun onStart() {
        super.onStart()
        (application as OpencodeApp).container.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        (application as OpencodeApp).container.setForeground(false)
    }

    override fun onResume() {
        super.onResume()
        // Re-check on resume: an Activity recreated by a config change not in configChanges
        // (e.g. locale) has savedInstanceState != null, so onCreate skipped the request. This
        // ensures the user is prompted once per Activity instance when still ungranted rather
        // than silently losing all notifications.
        maybeRequestNotificationPermission()
    }

    /** Capture text shared from another app so it can be prefilled into a session draft,
     *  and session ids from notification taps / deep links so we can open them. */
    private fun handleIntent(intent: Intent?) {
        val container = (application as OpencodeApp).container
        val action = intent?.action
        // "New session" from a launcher shortcut or the quick-settings tile. Guarded so a
        // process-death restore (which re-delivers the intent) doesn't spawn a second session.
        if (action == ACTION_NEW_SESSION && !newSessionHandled) {
            container.requestNewSession()
            newSessionHandled = true
        }
        if ((action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) && !shareIntentHandled) {
            val type = intent.type.orEmpty()
            if (action == Intent.ACTION_SEND && type.startsWith("image/")) {
                // A single shared image → stage as an attachment in the next opened session.
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) {
                    container.setPendingSharedMedia(listOf(uri.toString()))
                    shareIntentHandled = true
                }
            } else if (action == Intent.ACTION_SEND_MULTIPLE && type.startsWith("image/")) {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.take(NetworkConfig.maxAttachments)
                    .orEmpty()
                if (uris.isNotEmpty()) {
                    container.setPendingSharedMedia(uris.map { it.toString() })
                    shareIntentHandled = true
                }
            } else {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.takeIf { it.isNotBlank() }
                    ?.take(10_000) // cap to prevent unbounded memory usage from malicious shares
                if (text != null) {
                    container.setPendingShare(text)
                    shareIntentHandled = true
                }
            }
        }
        // Deep links under the opencode:// scheme. The manifest exposes several hosts:
        //   session/{id}  → open (and switch to) that conversation
        //   file/{path}   → open the file viewer at that workspace path
        //   new           → start a fresh session
        // The file path is percent-encoded by the link source; decode it here and validate the
        // segment to keep path-traversal out of the REST path (mirroring the session guard).
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        when (data?.host) {
            "session" -> {
                val seg = data.lastPathSegment
                if (!openSessionHandled) {
                    seg?.takeIf { it.isNotBlank() && it.matches(VALID_SESSION_ID) }?.let {
                        val profileId = intent.getStringExtra(NotificationActionReceiver.EXTRA_PROFILE_ID)
                            ?.takeIf { it.isNotBlank() }
                        container.requestOpenSession(it, profileId)
                        openSessionHandled = true
                    }
                }
            }
            "file" -> {
                // Reconstruct the path from the path segments after "file", preserving internal
                // slashes. Decoded + validated against traversal/control characters.
                val rawPath = data.path?.removePrefix("/file/")?.let { android.net.Uri.decode(it) }
                if (!openFileHandled) {
                    rawPath?.takeIf { it.isNotBlank() && VALID_FILE_PATH.containsMatchIn(it) && !it.contains("..") }
                        ?.let { container.requestOpenFile(it); openFileHandled = true }
                }
            }
            "new" -> {
                if (!newSessionHandled) {
                    container.requestNewSession()
                    newSessionHandled = true
                }
            }
        }
        // Fall back to the EXTRA_SESSION_ID extra (notifications / widget) when no deep link matched.
        if (!openSessionHandled) {
            intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() && it.matches(VALID_SESSION_ID) }?.let {
                val profileId = intent.getStringExtra(NotificationActionReceiver.EXTRA_PROFILE_ID)
                    ?.takeIf { it.isNotBlank() }
                container.requestOpenSession(it, profileId)
                openSessionHandled = true
            }
        }
    }

    companion object {
        /** Intent extra carrying a session id to open (notifications / deep links / widget). */
        const val EXTRA_SESSION_ID = "soy.iko.opencode.extra.SESSION_ID"

        /** Action for the "New session" launcher shortcut and quick-settings tile. */
        const val ACTION_NEW_SESSION = "soy.iko.opencode.action.NEW_SESSION"

        /** Session ids are alphanumeric with dashes/underscores. Reject path traversal. */
        private val VALID_SESSION_ID = Regex("[A-Za-z0-9_-]+")

        /** A file path must be made of path-ish characters and not carry traversal. Used to
         *  sanity-check the file deep link before handing it to the viewer. */
        private val VALID_FILE_PATH = Regex("[A-Za-z0-9_\\-./ ]+")

        private const val KEY_SHARE_HANDLED = "shareIntentHandled"
        private const val KEY_OPEN_SESSION_HANDLED = "openSessionHandled"
        private const val KEY_NEW_SESSION_HANDLED = "newSessionHandled"
        private const val KEY_OPEN_FILE_HANDLED = "openFileHandled"
        private const val KEY_NOTIF_PERM_REQUESTED = "notificationPermissionRequested"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val container = (application as OpencodeApp).container
        // Persist each intent-handled flag as "consumed", not merely "dispatched". The pending
        // signals live only in the in-memory AppContainer, which is recreated EMPTY on process
        // death. Persisting a plain "dispatched = true" would, after a kill *before* the UI
        // consumed the signal, restore the flag as true so the fresh container never receives it —
        // the deep-linked / new / shared session would silently never open. So AND each flag with
        // "the container no longer holds the signal": still-pending at save time → persist false so
        // the re-delivered intent re-dispatches it into the new container on restore; already
        // consumed → persist true so we don't re-open it unexpectedly on every later restore.
        val openConsumed = container.pendingOpenSession.value == null
        val newConsumed = container.pendingNewSession.value == 0
        val shareConsumed = container.pendingShare.value == null && container.pendingSharedMedia.value.isEmpty()
        val fileConsumed = container.pendingOpenFile.value == null
        outState.putBoolean(KEY_SHARE_HANDLED, shareIntentHandled && shareConsumed)
        outState.putBoolean(KEY_OPEN_SESSION_HANDLED, openSessionHandled && openConsumed)
        outState.putBoolean(KEY_NEW_SESSION_HANDLED, newSessionHandled && newConsumed)
        outState.putBoolean(KEY_OPEN_FILE_HANDLED, openFileHandled && fileConsumed)
        outState.putBoolean(KEY_NOTIF_PERM_REQUESTED, notificationPermissionRequested)
    }

    /** Ask for POST_NOTIFICATIONS on Android 13+ so run/completion notifications show.
     *  Idempotent per Activity instance via [notificationPermissionRequested].
     *  Shows a rationale dialog first (explaining what the user gains) before the system
     *  permission prompt, so a user who denies understands what they're missing. */
    private fun maybeRequestNotificationPermission() {
        if (notificationPermissionRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionRequested = true
            showNotificationRationaleDialog()
        }
    }

    private fun showNotificationRationaleDialog() {
        showNotifRationale = true
    }
}
