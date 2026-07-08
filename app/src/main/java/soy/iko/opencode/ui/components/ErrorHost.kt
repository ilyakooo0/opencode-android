package soy.iko.opencode.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import soy.iko.opencode.core.Core
import soy.iko.opencode.core.Event

/**
 * A [SnackbarHostState] that observes [ViewModel.error] and surfaces it to
 * the user. Offers a "Dismiss" action that sends [Event.DismissError] to
 * clear the error in the core so it doesn't re-show on recomposition.
 *
 * Used by every screen so error messages are never silently dropped.
 */
@Composable
fun ErrorHost(core: Core): SnackbarHostState {
    val view by core.view.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(view.error) {
        val message = view.error
        if (!message.isNullOrEmpty()) {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                core.update(Event.DismissError)
            }
        }
    }

    return snackbarHostState
}

@Composable
fun ErrorSnackbarHost(snackbarHostState: SnackbarHostState) {
    SnackbarHost(snackbarHostState) { data ->
        Snackbar(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            actionContentColor = MaterialTheme.colorScheme.onErrorContainer,
            snackbarData = data,
        )
    }
}
