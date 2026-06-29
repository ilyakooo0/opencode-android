package soy.iko.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import soy.iko.opencode.data.repo.ThemeMode
import soy.iko.opencode.ui.OpencodeApp as OpencodeAppUi
import soy.iko.opencode.ui.theme.OpencodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpencodeApp).container
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
}
