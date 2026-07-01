package soy.iko.opencode.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R
import soy.iko.opencode.data.network.EventStreamClient

/**
 * Banner shown when the SSE event stream is not connected. Shared by the chat and the
 * session list so a dropped stream is visible everywhere, not just mid-conversation.
 *
 * On a hard failure ([ConnectionState.Failed]) an inline "Retry now" button is offered
 * when [onRetry] is supplied, so the user can re-connect without hunting for the
 * chat screen's reconnect button or navigating to the server list. Transient
 * connecting/reconnecting states remain non-interactive (the system is already trying).
 */
@Composable
fun ConnectionBanner(
    state: EventStreamClient.ConnectionState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val text = when (state) {
        EventStreamClient.ConnectionState.Connecting -> stringResource(R.string.connecting)
        EventStreamClient.ConnectionState.Disconnected -> stringResource(R.string.reconnecting)
        EventStreamClient.ConnectionState.Failed -> stringResource(R.string.connection_failed)
        EventStreamClient.ConnectionState.Connected -> null
    }
    // AnimatedVisibility so the banner slides/fades in and out instead of appearing
    // and disappearing instantly — a state change that's especially jarring when the
    // connection flaps, and was called out as a rough edge in the UX audit.
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
        if (text == null) return@AnimatedVisibility
        // Distinguish a hard failure (e.g. bad credentials) from transient
        // connecting/reconnecting states by switching to the error palette, so
        // the banner conveys urgency without relying on text alone.
        val isFailed = state == EventStreamClient.ConnectionState.Failed
        val container = if (isFailed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.tertiaryContainer
        val onContainer = if (isFailed) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onTertiaryContainer
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .semantics {
                    // Announce connection state changes to TalkBack users so they're
                    // aware the stream dropped/reconnecting without visual cues.
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = text
                },
            color = container,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isFailed) {
                    CircularProgressIndicator(
                        Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = onContainer,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f, fill = false),
                )
                // Inline retry on the Failed banner so the user can recover without
                // leaving the screen. Hidden during transient states (the system is
                // already reconnecting) and when no callback is wired (callers that
                // don't have a reconnect path, e.g. the file browser).
                if (isFailed && onRetry != null) {
                    TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.retry_now), color = onContainer)
                    }
                }
            }
        }
    }
}
