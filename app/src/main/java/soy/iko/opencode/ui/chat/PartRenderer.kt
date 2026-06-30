package soy.iko.opencode.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.Part
import soy.iko.opencode.data.model.ReasoningPart
import soy.iko.opencode.data.model.StepFinishPart
import soy.iko.opencode.data.model.StepStartPart
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.ToolCompleted
import soy.iko.opencode.data.model.ToolError
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.ToolPending
import soy.iko.opencode.data.model.ToolRunning
import soy.iko.opencode.data.model.ToolState
import soy.iko.opencode.data.model.ToolUnknown
import soy.iko.opencode.data.model.UnknownPart
import soy.iko.opencode.ui.components.DiffView
import soy.iko.opencode.ui.components.ImageLoadContext
import soy.iko.opencode.ui.components.MarkdownText
import soy.iko.opencode.ui.components.RemoteImage
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.isImage
import soy.iko.opencode.ui.components.looksLikeDiff
import soy.iko.opencode.R

private const val COLLAPSED_LIMIT = 600

/**
 * Renders a single message [Part]. The exhaustive `when` over the sealed type gives
 * compile-time coverage; the [UnknownPart] arm keeps the UI forward-compatible.
 */
@Composable
fun PartView(part: Part, modifier: Modifier = Modifier, isRunning: Boolean = false, imageContext: ImageLoadContext? = null) {
    when (part) {
        is TextPart -> if (!part.ignored && part.text.isNotEmpty()) {
            MarkdownText(part.text, modifier = modifier)
        }
        is ReasoningPart -> ReasoningBlock(part.text, streaming = isRunning, modifier)
        is ToolPart -> ToolCallView(part, modifier)
        is FilePart -> if (part.isImage && imageContext != null && (part.source != null || !part.url.isNullOrBlank())) {
            RemoteImage(part, imageContext, modifier)
        } else {
            FileChip(part, modifier)
        }
        is StepStartPart -> {} // boundary marker — nothing to draw
        is StepFinishPart -> {} // metrics handled at message level
        is UnknownPart -> {} // forward-compat: unknown part types are ignored
    }
}

@Composable
private fun ReasoningBlock(text: String, streaming: Boolean, modifier: Modifier) {
    if (text.isBlank()) return
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val expandedState = stringResource(R.string.state_expanded)
    val collapsedState = stringResource(R.string.state_collapsed)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) expandedState else collapsedState
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (streaming) {
                CircularProgressIndicator(
                    Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (streaming) stringResource(R.string.thinking) else stringResource(R.string.thoughts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { copyToClipboard(context, "reasoning", text) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.copy), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolCallView(part: ToolPart, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolStatusIcon(part.state)
            Text(
                part.tool,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        val detail = when (val s = part.state) {
            is ToolCompleted -> s.output?.takeIf { it.isNotBlank() }
            is ToolError -> s.error ?: stringResource(R.string.error_generic)
            else -> null
        }
        if (detail != null) {
            val collapsed = remember(detail) { detail.take(COLLAPSED_LIMIT) }
            var expanded by remember(detail) { mutableStateOf(false) }
            val expandedState = stringResource(R.string.state_expanded)
            val collapsedState = stringResource(R.string.state_collapsed)
            val display = if (expanded || detail.length <= COLLAPSED_LIMIT) detail else collapsed
            if (looksLikeDiff(display)) {
                DiffView(display)
            } else {
                Text(
                    display,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (detail.length > COLLAPSED_LIMIT) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.semantics {
                        stateDescription = if (expanded) expandedState else collapsedState
                    },
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        if (expanded) stringResource(R.string.show_less) else stringResource(R.string.show_more),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            TextButton(
                onClick = { copyToClipboard(context, "output", detail) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.copy), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun ToolStatusIcon(state: ToolState) {
    when (state) {
        is ToolPending, is ToolRunning ->
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        is ToolCompleted ->
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.tool_completed),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        is ToolError ->
            Icon(
                Icons.Filled.Error,
                contentDescription = stringResource(R.string.tool_error),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        is ToolUnknown ->
            Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FileChip(part: FilePart, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            part.filename ?: part.url ?: stringResource(R.string.file),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
