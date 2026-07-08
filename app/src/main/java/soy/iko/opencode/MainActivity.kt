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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    val context = LocalContext.current
    var lastBackPress by remember { mutableLongStateOf(0L) }
    val snackbarHostState = remember { SnackbarHostState() }
    val exitHint = stringResource(R.string.press_back_again_to_exit)

    // Map the system back button to logical in-app navigation so it never
    // silently exits the app from a sub-screen. On the root Connect screen,
    // require a double press to exit (with a snackbar hint the first time).
    BackHandler(enabled = true) {
        when (view.screen) {
            Screen.CHAT -> core.update(Event.NavigateToSessions)
            Screen.SESSIONS -> core.update(Event.NavigateToConnect)
            Screen.CONNECT -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < EXIT_CONFIRMATION_MS) {
                    (context as? android.app.Activity)?.finish()
                } else {
                    lastBackPress = now
                }
            }
        }
    }

    // Show the "press back again to exit" hint via a snackbar instead of a
    // toast, for consistency with the rest of the app's feedback.
    LaunchedEffect(lastBackPress) {
        if (lastBackPress > 0L && view.screen == Screen.CONNECT) {
            snackbarHostState.showSnackbar(
                message = exitHint,
                duration = SnackbarDuration.Short,
            )
        }
    }

    // Directional slide between screens for a smoother navigation feel:
    // forward navigation slides the new screen in from the end side, back
    // navigation slides it in from the start side. A short fade is layered on
    // so the transition doesn't feel harsh. The TopAppBar still fades with
    // each screen, but the directional motion reads as natural page changes
    // rather than a flat crossfade.
    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
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
            modifier = Modifier.padding(padding),
        ) { screen ->
            when (screen) {
                Screen.CONNECT -> ConnectScreen(core)
                Screen.SESSIONS -> SessionsScreen(core)
                Screen.CHAT -> ChatScreen(core)
            }
        }
    }
}

private val EXIT_CONFIRMATION_MS = 2000L
