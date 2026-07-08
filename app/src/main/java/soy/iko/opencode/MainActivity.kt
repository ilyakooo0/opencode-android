package soy.iko.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import soy.iko.opencode.core.Core
import soy.iko.opencode.core.Event
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

    // Map the system back button to logical in-app navigation so it never
    // silently exits the app from a sub-screen.
    BackHandler(enabled = view.screen != Screen.CONNECT) {
        when (view.screen) {
            Screen.CHAT -> core.update(Event.NavigateToSessions)
            Screen.SESSIONS -> core.update(Event.NavigateToConnect)
            Screen.CONNECT -> { /* no-op; CONNECT is the root */ }
        }
    }

    // Crossfade between screens for a smoother navigation feel.
    AnimatedContent(
        targetState = view.screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen-transition",
    ) { screen ->
        when (screen) {
            Screen.CONNECT -> ConnectScreen(core)
            Screen.SESSIONS -> SessionsScreen(core)
            Screen.CHAT -> ChatScreen(core)
        }
    }
}
