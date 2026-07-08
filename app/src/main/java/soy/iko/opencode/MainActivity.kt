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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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

    // Directional slide between screens for a smoother navigation feel:
    // forward navigation slides the new screen in from the end side, back
    // navigation slides it in from the start side. A short fade is layered on
    // so the transition doesn't feel harsh. The TopAppBar still fades with
    // each screen, but the directional motion reads as natural page changes
    // rather than a flat crossfade.
    AnimatedContent(
        targetState = view.screen,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            if (forward) {
                slideInHorizontally(initialOffsetX = { it / 3 }) + fadeIn() togetherWith
                    slideOutHorizontally(targetOffsetX = { -it / 6 }) + fadeOut()
            } else {
                slideInHorizontally(initialOffsetX = { -it / 6 }) + fadeIn() togetherWith
                    slideOutHorizontally(targetOffsetX = { it / 3 }) + fadeOut()
            }
        },
        label = "screen-transition",
    ) { screen ->
        when (screen) {
            Screen.CONNECT -> ConnectScreen(core)
            Screen.SESSIONS -> SessionsScreen(core)
            Screen.CHAT -> ChatScreen(core)
        }
    }
}
