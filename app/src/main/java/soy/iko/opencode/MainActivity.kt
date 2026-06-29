package soy.iko.opencode

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import soy.iko.opencode.data.repo.ThemeMode
import soy.iko.opencode.ui.OpencodeApp as OpencodeAppUi
import soy.iko.opencode.ui.theme.OpencodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpencodeApp).container
        if (savedInstanceState == null) handleShareIntent(intent)
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
        handleShareIntent(intent)
    }

    /** Capture text shared from another app so it can be prefilled into a session draft. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        (application as OpencodeApp).container.setPendingShare(text)
    }
}
