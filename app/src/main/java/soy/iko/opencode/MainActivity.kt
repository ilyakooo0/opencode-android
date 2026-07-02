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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.let {
            shareIntentHandled = it.getBoolean(KEY_SHARE_HANDLED, false)
            openSessionHandled = it.getBoolean(KEY_OPEN_SESSION_HANDLED, false)
        }
        val container = (application as OpencodeApp).container
        handleIntent(intent)
        if (savedInstanceState == null) maybeRequestNotificationPermission()
        // Hold the splash until the persisted theme settings load, so we never paint a
        // frame with the default (dynamicColor=true / SYSTEM) that then snaps to the
        // user's real choice — the flash the SettingsScreen's null-gate already avoids.
        var themeLoaded = false
        splash.setKeepOnScreenCondition { !themeLoaded }
        setContent {
            val theme by remember(container) {
                container.settingsStore.themeMode
                    .combine(container.settingsStore.dynamicColor, ::Pair)
            }.collectAsStateWithLifecycle(initialValue = null)
            val appLock by container.settingsStore.appLock.collectAsStateWithLifecycle(initialValue = false)
            LaunchedEffect(theme) { if (theme != null) themeLoaded = true }
            // Hide the app's content from screenshots / the recents thumbnail while app lock
            // is enabled, so protected server details don't leak there.
            LaunchedEffect(appLock) {
                if (appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            theme?.let { (themeMode, dynamicColor) ->
                val dark = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                OpencodeTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                    AppLockGate(enabled = appLock) {
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

    /** Capture text shared from another app so it can be prefilled into a session draft,
     *  and session ids from notification taps / deep links so we can open them. */
    private fun handleIntent(intent: Intent?) {
        val container = (application as OpencodeApp).container
        val action = intent?.action
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
                container.requestOpenSession(it)
                openSessionHandled = true
            }
        }
    }

    companion object {
        /** Intent extra carrying a session id to open (notifications / deep links). */
        const val EXTRA_SESSION_ID = "soy.iko.opencode.extra.SESSION_ID"

        /** Session ids are alphanumeric with dashes/underscores. Reject path traversal. */
        private val VALID_SESSION_ID = Regex("[A-Za-z0-9_-]+")

        private const val KEY_SHARE_HANDLED = "shareIntentHandled"
        private const val KEY_OPEN_SESSION_HANDLED = "openSessionHandled"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHARE_HANDLED, shareIntentHandled)
        outState.putBoolean(KEY_OPEN_SESSION_HANDLED, openSessionHandled)
    }

    /** Ask for POST_NOTIFICATIONS once on Android 13+ so run/completion notifications show. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
