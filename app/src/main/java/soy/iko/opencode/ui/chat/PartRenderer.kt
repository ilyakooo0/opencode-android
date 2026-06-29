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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
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

/**
 * Renders a single message [Part]. The exhaustive `when` over the sealed type gives
 * compile-time coverage; the [UnknownPart] arm keeps the UI forward-compatible.
 * (Markdown rendering is deferred — text is shown as-is for the scaffold.)
 */
@Composable
fun PartView(part: Part, modifier: Modifier = Modifier) {
    when (part) {
        is TextPart -> if (!part.ignored && part.text.isNotEmpty()) {
            Text(part.text, modifier = modifier, style = MaterialTheme.typography.bodyLarge)
        }
        is ReasoningPart -> ReasoningBlock(part.text, modifier)
        is ToolPart -> ToolCallView(part, modifier)
        is FilePart -> FileChip(part, modifier)
        is StepStartPart -> {} // boundary marker — nothing to draw
        is StepFinishPart -> {} // metrics handled at message level
        is UnknownPart -> {} // forward-compat: unknown part types are ignored
    }
}

@Composable
private fun ReasoningBlock(text: String, modifier: Modifier) {
    if (text.isBlank()) return
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (expanded) "  Thinking" else "  Thinking…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolCallView(part: ToolPart, modifier: Modifier) {
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
                "  ${part.tool}",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
        val detail = when (val s = part.state) {
            is ToolCompleted -> s.output?.takeIf { it.isNotBlank() }
            is ToolError -> s.error ?: "error"
            else -> null
        }
        if (detail != null) {
            Text(
                detail.take(2000),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ToolStatusIcon(state: ToolState) {
    when (state) {
        is ToolPending, is ToolRunning ->
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        is ToolCompleted ->
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        is ToolError ->
            Icon(Icons.Filled.Error, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        is ToolUnknown ->
            Icon(Icons.Filled.Bolt, null, Modifier.size(16.dp))
    }
}

@Composable
private fun FileChip(part: FilePart, modifier: Modifier) {
    Text(
        "📎 ${part.filename ?: part.url ?: "file"}",
        modifier = modifier.padding(vertical = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
}
