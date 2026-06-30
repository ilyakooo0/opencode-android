package soy.iko.opencode.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 */
@Composable
fun ConnectionBanner(
    state: EventStreamClient.ConnectionState,
    modifier: Modifier = Modifier,
) {
    val text = when (state) {
        EventStreamClient.ConnectionState.Connecting -> stringResource(R.string.connecting)
        EventStreamClient.ConnectionState.Disconnected -> stringResource(R.string.reconnecting)
        EventStreamClient.ConnectionState.Failed -> stringResource(R.string.connection_failed)
        EventStreamClient.ConnectionState.Connected -> null
    }
    if (text != null) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .semantics {
                    // Announce connection state changes to TalkBack users so they're
                    // aware the stream dropped/reconnecting without visual cues.
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = text
                },
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
