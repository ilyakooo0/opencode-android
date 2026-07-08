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
import kotlinx.coroutines.flow.SharedFlow
import soy.iko.opencode.core.Core
import soy.iko.opencode.core.Event

/**
 * A [SnackbarHostState] that observes [ViewModel.error] and surfaces it to
 * the user. Offers a "Dismiss" action that sends [Event.DismissError] to
 * clear the error in the core so it doesn't re-show on recomposition.
 *
 * When the core has a retryable last event, a "Retry" action is also offered
 * (it takes precedence over "Dismiss" since it's more useful). Tapping Retry
 * re-sends the last user-initiated event.
 *
 * Used by every screen so error messages are never silently dropped.
 */
@Composable
fun ErrorHost(
    core: Core,
    dismissLabel: String = "Dismiss",
    retryLabel: String = "Retry",
): SnackbarHostState {
    val view by core.view.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(view.error) {
        val message = view.error
        if (!message.isNullOrEmpty()) {
            val canRetry = core.retryableEvent != null
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (canRetry) retryLabel else dismissLabel,
                duration = SnackbarDuration.Long,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> {
                    if (canRetry) core.retry() else core.update(Event.DismissError)
                }
                SnackbarResult.Dismissed -> core.update(Event.DismissError)
            }
        }
    }

    return snackbarHostState
}

/**
 * Collects transient success/info messages from [Core.info] and surfaces them
 * as a short snackbar. This gives the user positive feedback (e.g. "Session
 * created") instead of only ever showing errors.
 */
@Composable
fun InfoHost(
    core: Core,
    successConnectedLabel: String,
    successSessionCreatedLabel: String,
    snackbarHostState: SnackbarHostState,
) {
    LaunchedEffect(snackbarHostState) {
        core.info.collect { key ->
            val message = when (key) {
                Core.SUCCESS_CONNECTED -> successConnectedLabel
                Core.SUCCESS_SESSION_CREATED -> successSessionCreatedLabel
                else -> key
            }
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }
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
