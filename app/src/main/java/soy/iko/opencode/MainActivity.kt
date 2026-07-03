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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.combine
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                    AppLockGate(enabled = locked) {
                        OpencodeAppUi(container = container)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareIntentHandled = false
        openSessionHandled = false
        newSessionHandled = false
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
        // Deep link: opencode://session/{sessionId}  (or the EXTRA_SESSION_ID extra).
        val deepLinkId = intent?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data
            ?.takeIf { it.host == "session" }
            ?.lastPathSegment
        val id = deepLinkId ?: intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
        // Validate: session ids are opaque server-generated identifiers. Reject anything
        // with path separators or other traversal/control characters so a malicious deep
        // link can't inject path components into the REST URL path.
        // Guard against re-firing the retained ACTION_VIEW intent after process-death
        // restore: onCreate calls handleIntent again with the original intent, so without
        // this flag (mirroring shareIntentHandled) the session would re-open unexpectedly.
        if (!openSessionHandled) {
            id?.takeIf { it.isNotBlank() && it.matches(VALID_SESSION_ID) }?.let {
                val profileId = intent?.getStringExtra(NotificationActionReceiver.EXTRA_PROFILE_ID)
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

        private const val KEY_SHARE_HANDLED = "shareIntentHandled"
        private const val KEY_OPEN_SESSION_HANDLED = "openSessionHandled"
        private const val KEY_NEW_SESSION_HANDLED = "newSessionHandled"
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
        outState.putBoolean(KEY_SHARE_HANDLED, shareIntentHandled && shareConsumed)
        outState.putBoolean(KEY_OPEN_SESSION_HANDLED, openSessionHandled && openConsumed)
        outState.putBoolean(KEY_NEW_SESSION_HANDLED, newSessionHandled && newConsumed)
        outState.putBoolean(KEY_NOTIF_PERM_REQUESTED, notificationPermissionRequested)
    }

    /** Ask for POST_NOTIFICATIONS on Android 13+ so run/completion notifications show.
     *  Idempotent per Activity instance via [notificationPermissionRequested]. */
    private fun maybeRequestNotificationPermission() {
        if (notificationPermissionRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionRequested = true
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
