package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
