package soy.iko.opencode.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var showStopConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(
                message = it,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short,
            )
            // Always clear the error from state after showing.
            dispatch(Event.DismissError)
        }
    }

    BackHandler(enabled = state.screen != Screen.Connect) {
        when (state.screen) {
            // Leaving mid-generation would silently drop the reply, so confirm first.
            Screen.Chat -> if (state.generating) showStopConfirm = true else dispatch(Event.NavigateToSessions)
            Screen.Sessions -> dispatch(Event.NavigateToConnect)
            Screen.Connect -> Unit
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop generation?") },
            text = { Text("Leaving will stop the current generation.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    dispatch(Event.CancelGeneration)
                    dispatch(Event.NavigateToSessions)
                }) {
                    Text("Stop and leave")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) {
                    Text("Stay")
                }
            },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        AnimatedContent(
            targetState = state.screen,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                // Compare screen ordinals to decide slide direction: forward navigation
                // (Connect → Sessions → Chat) slides in from the right, back slides from the left.
                if (targetState.ordinal > initialState.ordinal) {
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                } else {
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                }
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
