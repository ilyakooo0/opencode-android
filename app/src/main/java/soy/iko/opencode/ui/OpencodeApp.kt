package soy.iko.opencode.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import soy.iko.opencode.core.Event
import soy.iko.opencode.core.Screen
import soy.iko.opencode.core.UiState
import soy.iko.opencode.ui.screens.ChatScreen
import soy.iko.opencode.ui.screens.ConnectScreen
import soy.iko.opencode.ui.screens.SessionsScreen

@Composable
fun OpencodeApp(state: UiState, dispatch: (Event) -> Unit) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            dispatch(Event.DismissError)
        }
    }

    BackHandler(enabled = state.screen != Screen.Connect) {
        when (state.screen) {
            Screen.Chat -> dispatch(Event.NavigateToSessions)
            Screen.Sessions -> dispatch(Event.NavigateToConnect)
            Screen.Connect -> Unit
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        AnimatedContent(
            targetState = state.screen,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                (fadeIn() togetherWith fadeOut()).using(SizeTransform(clip = false))
            },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.Connect -> ConnectScreen(state, dispatch)
                Screen.Sessions -> SessionsScreen(state, dispatch)
                Screen.Chat -> ChatScreen(state, dispatch)
            }
        }
    }
}
