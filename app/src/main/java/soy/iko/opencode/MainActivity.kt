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
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpencodeApp).container
        if (savedInstanceState == null) handleIntent(intent)
        maybeRequestNotificationPermission()
        setContent {
            val themeMode by container.settingsStore.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by container.settingsStore.dynamicColor
                .collectAsStateWithLifecycle(initialValue = true)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Capture text shared from another app so it can be prefilled into a session draft,
     *  and session ids from notification taps / deep links so we can open them. */
    private fun handleIntent(intent: Intent?) {
        val container = (application as OpencodeApp).container
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            if (text != null) container.setPendingShare(text)
        }
        // Deep link: opencode://session/{sessionId}  (or the EXTRA_SESSION_ID extra).
        val deepLinkId = intent?.takeIf { it.action == Intent.ACTION_VIEW }
            ?.data
            ?.takeIf { it.host == "session" }
            ?.lastPathSegment
        val id = deepLinkId ?: intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
        id?.let { container.requestOpenSession(it) }
    }

    companion object {
        /** Intent extra carrying a session id to open (notifications / deep links). */
        const val EXTRA_SESSION_ID = "soy.iko.opencode.extra.SESSION_ID"
    }

    /** Ask for POST_NOTIFICATIONS once on Android 13+ so run/completion notifications show. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
