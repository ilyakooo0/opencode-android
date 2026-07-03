package soy.iko.opencode.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import soy.iko.opencode.R

/**
 * The staged-attachment UI for the chat composer — extracted from ChatScreen so that (large)
 * file stays focused on the conversation surface. `internal` so ChatScreen can call it.
 */
@Composable
internal fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    onRemove: (String) -> Unit,
    staging: Boolean = false,
) {
    // Show the strip while an encode is in flight even before the first chip exists, so the
    // user gets immediate feedback that their pick is being processed.
    if (attachments.isEmpty() && !staging) return
    // Horizontally-scrollable strip of staged attachment thumbnails/chips, each removable.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { att ->
            AttachmentChip(att, onRemove = { onRemove(att.id) })
        }
        // A placeholder chip with an indeterminate spinner while any pick is still being
        // read + base64-encoded off the main thread (chips only materialize once done).
        if (staging) StagingChip()
    }
}

/** A placeholder chip shown while an attachment is being encoded. */
@Composable
private fun StagingChip() {
    val stagingLabel = stringResource(R.string.attachment_staging)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            CircularProgressIndicator(
                Modifier.size(18.dp).semantics { contentDescription = stagingLabel },
                strokeWidth = 2.dp,
            )
            Text(
                stagingLabel,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
            )
        }
    }
}

/** A single staged attachment: an image thumbnail (or a generic file icon) with its name
 *  and a remove button. */
@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
            if (attachment.previewModel != null) {
                AsyncImage(
                    model = attachment.previewModel,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(36.dp).clip(MaterialTheme.shapes.extraSmall),
                )
            } else {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                attachment.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp).padding(horizontal = 6.dp),
            )
            // No .size() override so the IconButton keeps its default 48dp touch target
            // (a11y minimum) while the visual X stays small (16dp).
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.remove),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
