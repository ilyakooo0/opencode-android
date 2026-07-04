package soy.iko.opencode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import soy.iko.opencode.R
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.di.AppContainer

/** Attempt count at which the banner switches to "Reconnecting (attempt N)…". See
 *  NetworkConfig.sseReconnectAttemptLabelThreshold. */
private val RECONNECT_ATTEMPT_LABEL_THRESHOLD get() = NetworkConfig.sseReconnectAttemptLabelThreshold

/**
 * Banner shown when the SSE event stream is not connected. Shared by the chat and the
 * session list so a dropped stream is visible everywhere, not just mid-conversation.
 *
 * On a hard failure ([ConnectionState.Failed]) an inline "Retry now" button is offered
 * when [onRetry] is supplied, so the user can re-connect without hunting for the
 * chat screen's reconnect button or navigating to the server list. On an auth failure
 * ([ConnectionState.AuthFailed]) an "Edit credentials" button is offered instead (via
 * [onEditCredentials]) — retrying with the same bad credentials is futile, so the
 * recovery path is to edit the server profile. Transient connecting/reconnecting states
 * remain non-interactive (the system is already trying).
 */
@Composable
fun ConnectionBanner(
    state: EventStreamClient.ConnectionState,
    modifier: Modifier = Modifier,
    isOnline: Boolean = true,
    onRetry: (() -> Unit)? = null,
    onEditCredentials: (() -> Unit)? = null,
    reconnectAttempts: Int = 0,
) {
    // When the device itself has no connectivity, surface that distinctly (instead of
    // the SSE state) — a dropped Wi-Fi shouldn't read "Reconnecting…" or "check
    // credentials", neither of which reflects the real problem.
    val text = if (!isOnline) {
        stringResource(R.string.offline)
    } else {
        when (state) {
            EventStreamClient.ConnectionState.Connecting ->
                if (reconnectAttempts >= RECONNECT_ATTEMPT_LABEL_THRESHOLD) {
                    stringResource(R.string.reconnect_attempt, reconnectAttempts)
                } else {
                    stringResource(R.string.connecting)
                }
            EventStreamClient.ConnectionState.Disconnected ->
                if (reconnectAttempts >= RECONNECT_ATTEMPT_LABEL_THRESHOLD) {
                    stringResource(R.string.reconnect_attempt, reconnectAttempts)
                } else {
                    stringResource(R.string.reconnecting)
                }
            EventStreamClient.ConnectionState.Failed -> stringResource(R.string.connection_failed_endpoint)
            EventStreamClient.ConnectionState.AuthFailed -> stringResource(R.string.connection_failed)
            EventStreamClient.ConnectionState.Connected -> null
        }
    }
    val isOffline = !isOnline
    // AnimatedVisibility so the banner slides/fades in and out instead of appearing
    // and disappearing instantly — a state change that's especially jarring when the
    // connection flaps, and was called out as a rough edge in the UX audit.
    // Retain the last non-null banner text so the exit fade-out has content to render.
    // The content lambda recomposes with the now-null `text` during the exit animation;
    // early-returning on null there would leave nothing to fade, so the banner would just
    // vanish instead of fading out.
    val lastText = remember { mutableStateOf(text) }
    // Write the retained text in a SideEffect (not directly in the body): writing state during
    // composition is a documented anti-pattern that can trigger extra recompositions and isn't
    // guaranteed a defined ordering. SideEffect runs only after a successful composition.
    SideEffect { if (text != null) lastText.value = text }
    AnimatedVisibility(
        visible = text != null,
        enter = rememberVisibilityTransitions().enter,
        exit = rememberVisibilityTransitions().exit,
    ) {
        val shown = lastText.value ?: return@AnimatedVisibility
        // Distinguish a hard failure (e.g. bad credentials) and an offline device from
        // transient connecting/reconnecting states by switching to the error palette,
        // so the banner conveys urgency without relying on text alone.
        val isFailed = isOffline || state == EventStreamClient.ConnectionState.Failed ||
            state == EventStreamClient.ConnectionState.AuthFailed
        // Server-side failure (auth or endpoint) — distinct from offline. Used to gate the
        // inline Retry button, which only makes sense when the device is online and the server
        // rejected the connection (not when the device itself is offline).
        val isServerFailure = state == EventStreamClient.ConnectionState.Failed ||
            state == EventStreamClient.ConnectionState.AuthFailed
        val container by animateColorAsState(
            targetValue = if (isFailed) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.tertiaryContainer,
            animationSpec = tween(NetworkConfig.motionFadeDurationMs),
            label = "bannerContainer",
        )
        val onContainer by animateColorAsState(
            targetValue = if (isFailed) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onTertiaryContainer,
            animationSpec = tween(NetworkConfig.motionFadeDurationMs),
            label = "bannerOnContainer",
        )
        Surface(
            modifier = modifier
                .fillMaxWidth()
                // mergeDescendants so the spinner + label read as one TalkBack stop instead of
                // several; the Retry TextButton is its own merged node, so it stays separately
                // actionable.
                .semantics(mergeDescendants = true) {
                    // Announce connection state changes to TalkBack users so they're
                    // aware the stream dropped/reconnecting without visual cues.
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = shown
                },
            color = container,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isFailed) {
                    // An error icon reinforces the failure state visually, matching the
                    // session list's error state which uses ErrorOutline.
                    Icon(
                        Icons.Filled.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = onContainer,
                    )
                } else {
                    LoadingSpinner(
                        size = LoadingSize.Inline,
                        color = onContainer,
                    )
                }
                Text(
                    shown,
                    style = MaterialTheme.typography.labelMedium,
                    color = onContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f, fill = false),
                )
                // Inline recovery action on a hard failure so the user can recover without
                // leaving the screen. Hidden during transient states (the system is already
                // reconnecting), when offline (retry can't help), and when no callback is wired.
                if (isServerFailure && !isOffline) {
                    ConnectionRecoveryButton(
                        state = state,
                        onRetry = onRetry,
                        onEditCredentials = onEditCredentials,
                        onContainer = onContainer,
                    )
                }
            }
        }
    }
}

/** Renders the inline recovery button for a hard connection failure. For an auth failure
 *  (401/403), retrying with the same bad credentials is futile — offer "Edit credentials"
 *  instead. For an endpoint failure, "Retry now" re-attempts the connection. Extracted from
 *  [ConnectionBanner] to keep that function's cyclomatic complexity under detekt's threshold. */
@Composable
private fun ConnectionRecoveryButton(
    state: EventStreamClient.ConnectionState,
    onRetry: (() -> Unit)?,
    onEditCredentials: (() -> Unit)?,
    onContainer: androidx.compose.ui.graphics.Color,
) {
    if (state == EventStreamClient.ConnectionState.AuthFailed && onEditCredentials != null) {
        TextButton(
            onClick = onEditCredentials,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .testTag("connection_edit_credentials"),
        ) {
            Text(stringResource(R.string.edit_credentials), color = onContainer)
        }
    } else if (onRetry != null) {
        TextButton(
            onClick = onRetry,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .testTag("connection_retry"),
        ) {
            Text(stringResource(R.string.retry_now), color = onContainer)
        }
    }
}

/**
 * Convenience wrapper that collects the SSE connection state and device connectivity from
 * [container] and renders a [ConnectionBanner] aligned to the top center of the calling
 * [BoxScope]. Screens that don't already expose connection state (Files, Settings,
 * Diagnostics) can call this instead of duplicating the flatMapLatest boilerplate.
 * The retry callback triggers an SSE reconnect on the active connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun BoxScope.ConnectionBannerFor(
    container: AppContainer,
    onEditCredentials: (() -> Unit)? = null,
) {
    // flatMapLatest without stateIn: collectAsStateWithLifecycle handles the lifecycle and
    // gives an initial value. No ViewModel scope needed, so this works on any screen.
    val connectionState by produceState(
        initialValue = EventStreamClient.ConnectionState.Disconnected,
        container,
    ) {
        container.activeConnection
            .flatMapLatest { it?.events?.state ?: flowOf(EventStreamClient.ConnectionState.Disconnected) }
            .collect { value = it }
    }
    val reconnectAttempts by produceState(initialValue = 0, container) {
        container.activeConnection
            .flatMapLatest { it?.events?.reconnectAttempts ?: flowOf(0) }
            .collect { value = it }
    }
    val isOnline by container.isOnline.collectAsStateWithLifecycle()
    ConnectionBanner(
        state = connectionState,
        modifier = Modifier.align(Alignment.TopCenter),
        isOnline = isOnline,
        onRetry = { container.activeConnection.value?.events?.triggerReconnect() },
        onEditCredentials = onEditCredentials,
        reconnectAttempts = reconnectAttempts,
    )
}
