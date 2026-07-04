package soy.iko.opencode.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import soy.iko.opencode.R
import soy.iko.opencode.data.network.NetworkConfig
import java.util.Locale

/**
 * The staged-attachment UI for the chat composer — extracted from ChatScreen so that (large)
 * file stays focused on the conversation surface. `internal` so ChatScreen can call it.
 *
 * When at least one attachment is staged, a muted total-size label is rendered at the end of
 * the strip so the user can see how close they are to the cumulative [NetworkConfig.maxTotalAttachmentBytes]
 * cap — a bare per-file cap doesn't surface the combined total, and a failed send is the only
 * other feedback.
 */
@Composable
internal fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    onRemove: (String) -> Unit,
    staging: Boolean = false,
    stagingFileCount: Int = 0,
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { att ->
            AttachmentChip(att, onRemove = { onRemove(att.id) })
        }
        // A placeholder chip with an indeterminate spinner while any pick is still being
        // read + base64-encoded off the main thread (chips only materialize once done).
        if (staging) StagingChip(stagingFileCount)
        // Total staged size label — surfaced so the user can see how close they are to the
        // cumulative cap before a send fails. Hidden while staging (the total is in flux).
        if (!staging && attachments.isNotEmpty()) {
            val totalBytes = remember(attachments) {
                attachments.sumOf { base64DataUrlByteSize(it.part.url) }
            }
            val maxBytes = NetworkConfig.maxTotalAttachmentBytes
            val label = remember(totalBytes, maxBytes) { formatAttachmentTotal(totalBytes, maxBytes) }
            val over = totalBytes > maxBytes
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            )
        }
    }
}

/** Format the staged-attachment total as "N.N MB / max" (or KB when small), for the strip label. */
private fun formatAttachmentTotal(bytes: Long, max: Long): String {
    val mb = 1024.0 * 1024.0
    val kb = 1024.0
    val used = when {
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
    val maxStr = when {
        max >= mb -> String.format(Locale.US, "%.0f MB", max / mb)
        max >= kb -> String.format(Locale.US, "%.0f KB", max / kb)
        else -> "$max B"
    }
    return "$used / $maxStr"
}

/** A placeholder chip shown while an attachment is being encoded. */
@Composable
private fun StagingChip(stagingFileCount: Int) {
    // Show "Staging N files…" when a multi-file count is known; fall back to the generic
    // "Attaching…" for the single/unknown case so the label never reads "Staging 1 files".
    val label = if (stagingFileCount > 1) {
        stringResource(R.string.staging_n_files, stagingFileCount)
    } else {
        stringResource(R.string.attachment_staging)
    }
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
                Modifier.size(18.dp).semantics { contentDescription = label },
                strokeWidth = 2.dp,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
            )
        }
    }
}

/** A single staged attachment: an image thumbnail (or a generic file icon) with its name
 *  and a remove button. Tapping the thumbnail/name of an image attachment opens a fullscreen
 *  preview so the user can verify it before sending (a sent image costs tokens and can't be
 *  unsent). */
@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    var showPreview by remember { mutableStateOf(false) }
    val previewLabel = stringResource(R.string.preview_attachment)
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
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .semantics { contentDescription = previewLabel }
                        .clickable(role = Role.Image) { showPreview = true },
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
    if (showPreview && attachment.previewModel != null) {
        Dialog(
            onDismissRequest = { showPreview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = attachment.previewModel,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Image) { showPreview = false },
                )
                IconButton(
                    onClick = { showPreview = false },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            }
        }
    }
}
