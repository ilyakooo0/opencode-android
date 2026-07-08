package soy.iko.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.core.Core
import soy.iko.opencode.core.Screen
import soy.iko.opencode.ui.screens.ChatScreen
import soy.iko.opencode.ui.screens.ConnectScreen
import soy.iko.opencode.ui.screens.SessionsScreen
import soy.iko.opencode.ui.theme.OpencodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpencodeTheme {
                App()
            }
        }
    }
}

@Composable
fun App(core: Core = viewModel()) {
    val view by core.view.collectAsState()

    when (view.screen) {
        Screen.CONNECT -> ConnectScreen(core)
        Screen.SESSIONS -> SessionsScreen(core)
        Screen.CHAT -> ChatScreen(core)
    }
}
