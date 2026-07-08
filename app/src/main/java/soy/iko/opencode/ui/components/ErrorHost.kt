package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.Column
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
 *
 * Uses a dedicated [SnackbarHostState] (separate from the error host) so the
 * info snackbar can be rendered with default colors rather than the error
 * palette. The screens render both hosts in the Scaffold's `snackbarHost`
 * slot; typically only one fires at a time.
 */
@Composable
fun InfoHost(
    core: Core,
    successConnectedLabel: String,
    successSessionCreatedLabel: String,
    copiedLabel: String = "Copied",
    sessionDeletedLabel: String = "Session deleted",
): SnackbarHostState {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarHostState) {
        core.info.collect { key ->
            val message = when (key) {
                Core.SUCCESS_CONNECTED -> successConnectedLabel
                Core.SUCCESS_SESSION_CREATED -> successSessionCreatedLabel
                Core.SUCCESS_COPIED -> copiedLabel
                Core.SUCCESS_SESSION_DELETED -> sessionDeletedLabel
                else -> key
            }
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
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

/**
 * Snackbar host for success/info messages. Uses the default (surface-tonal)
 * colors so positive feedback doesn't read as an error.
 */
@Composable
fun InfoSnackbarHost(snackbarHostState: SnackbarHostState) {
    SnackbarHost(snackbarHostState) { data ->
        Snackbar(snackbarData = data)
    }
}

/**
 * Renders both the error and info snackbar hosts stacked vertically. The
 * Material 3 [Scaffold] only accepts a single `snackbarHost` slot, so screens
 * pass this helper to show both flavors without one overwriting the other.
 */
@Composable
fun DualSnackbarHost(
    errorHostState: SnackbarHostState,
    infoHostState: SnackbarHostState,
) {
    Column {
        ErrorSnackbarHost(errorHostState)
        InfoSnackbarHost(infoHostState)
    }
}
