package soy.iko.opencode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import soy.iko.opencode.data.repo.ThemeMode
import soy.iko.opencode.ui.OpencodeApp as OpencodeAppUi
import soy.iko.opencode.ui.theme.OpencodeTheme

class MainActivity : ComponentActivity() {

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
            LaunchedEffect(theme) { if (theme != null) themeLoaded = true }
            theme?.let { (themeMode, dynamicColor) ->
                val dark = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                OpencodeTheme(darkTheme = dark, dynamicColor = dynamicColor) {
                    OpencodeAppUi(container = container)
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

    /** Capture text shared from another app so it can be prefilled into a session draft,
     *  and session ids from notification taps / deep links so we can open them. */
    private fun handleIntent(intent: Intent?) {
        val container = (application as OpencodeApp).container
        if (intent?.action == Intent.ACTION_SEND && !shareIntentHandled) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.takeIf { it.isNotBlank() }
                ?.take(10_000) // cap to prevent unbounded memory usage from malicious shares
            if (text != null) {
                container.setPendingShare(text)
                shareIntentHandled = true
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
