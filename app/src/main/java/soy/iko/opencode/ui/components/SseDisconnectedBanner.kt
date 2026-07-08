package soy.iko.opencode.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import soy.iko.opencode.core.SseState
import soy.iko.opencode.ui.theme.Dimens
import soy.iko.opencode.ui.theme.OpencodeTheme

/**
 * A thin banner shown above the message list when the SSE live stream drops.
 * Offers a "Reconnect" action and a "Dismiss" action so the user can keep
 * reading history without the banner persisting. The banner is a polite live
 * region so screen readers announce the disconnection once.
 *
 * Only render this when [SseState] is [SseState.Error] or an unexpected
 * [SseState.Disconnected] while a chat is active.
 */
@Composable
fun SseDisconnectedBanner(
    message: String,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    reconnectLabel: String = "Reconnect",
    dismissLabel: String = "Dismiss",
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = message
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.spaceLarge,
                    vertical = Dimens.spaceSmall,
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSmall),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = null)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                TextButton(onClick = onReconnect) { Text(reconnectLabel) }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SseDisconnectedBannerPreview() {
    OpencodeTheme {
        SseDisconnectedBanner(
            message = "Live stream disconnected",
            onReconnect = {},
            onDismiss = {},
        )
    }
}

/**
 * A thin non-actionable status banner (e.g. "Connecting…") shown above the
 * message list. Uses a neutral color scheme so it doesn't read as an error.
 */
@Composable
fun SseStatusBanner(
    message: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = message
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.spaceLarge,
                    vertical = Dimens.spaceSmall,
                ),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * A compact "Live" pill with a pulsing dot, shown in the chat TopAppBar when
 * the SSE stream is [SseState.Connected]. Gives users positive confirmation
 * that live updates are flowing — the app otherwise only surfaces *negative*
 * SSE states (disconnected / connecting). Small enough to sit inline in the
 * app bar actions row without crowding the overflow menu.
 *
 * Marked as a polite live region so TalkBack announces "Live" when streaming
 * starts, without stealing focus.
 */
@Composable
fun SseLiveIndicator(
    label: String,
    modifier: Modifier = Modifier,
) {
    val pulseTransition = rememberInfiniteTransition(label = "sse-live-pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sse-live-pulse-alpha",
    )
    val dotColor = MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = label
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceTiny),
            modifier = Modifier.padding(
                horizontal = Dimens.spaceSmall,
                vertical = Dimens.spaceTiny,
            ),
        ) {
            Canvas(modifier = Modifier.size(Dimens.iconInlineSpinner)) {
                drawCircle(
                    color = dotColor.copy(alpha = pulseAlpha.coerceIn(0.2f, 1f)),
                    radius = size.minDimension / 2f,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SseLiveIndicatorPreview() {
    OpencodeTheme {
        SseLiveIndicator(label = "Live")
    }
}
