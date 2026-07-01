package soy.iko.opencode.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import soy.iko.opencode.ui.components.showToast
import soy.iko.opencode.R

private const val COLLAPSED_LIMIT = 600

// Pretty-printer for tool inputs. Separate from OpencodeJson (which is tuned for
// resilient decoding) so this stays human-readable; constructed lazily and memoized.
private val prettyJson: Json = Json { prettyPrint = true }

/** Extract a human-readable title (e.g. "Reading src/main.kt") from a tool state. */
private fun ToolState.titleText(): String? = when (this) {
    is ToolRunning -> title
    is ToolCompleted -> title
    else -> null
}

/** Extract the raw input arguments of a tool call, if any. */
private fun ToolState.inputJson(): JsonElement? = when (this) {
    is ToolRunning -> input
    is ToolCompleted -> input
    is ToolError -> input
    else -> null
}

/**
 * Renders a single message [Part]. The exhaustive `when` over the sealed type gives
 * compile-time coverage; the [UnknownPart] arm keeps the UI forward-compatible.
 *
 * [onOpenFile] is invoked when the user taps a [FilePart] chip that references a
 * source path the viewer can open; null leaves the chip as a copy-path affordance
 * (used when no file navigation is wired, e.g. in tests).
 */
@Composable
fun PartView(
    part: Part,
    modifier: Modifier = Modifier,
    isRunning: Boolean = false,
    imageContext: ImageLoadContext? = null,
    onOpenFile: ((path: String) -> Unit)? = null,
) {
    when (part) {
        is TextPart -> if (!part.ignored && part.text.isNotEmpty()) {
            MarkdownText(part.text, modifier = modifier, streaming = isRunning)
        }
        is ReasoningPart -> ReasoningBlock(part.text, streaming = isRunning, keyId = part.id, modifier = modifier)
        is ToolPart -> ToolCallView(part, modifier)
        is FilePart -> if (part.isImage && imageContext != null && (part.source != null || !part.url.isNullOrBlank())) {
            RemoteImage(part, imageContext, modifier)
        } else {
            FileChip(part, modifier, onOpenFile)
        }
        is StepStartPart -> {} // boundary marker — nothing to draw
        is StepFinishPart -> {} // metrics handled at message level
        is UnknownPart -> UnknownPartNote(modifier)
    }
}

@Composable
private fun UnknownPartNote(modifier: Modifier) {
    // Forward-compat: a part type the client doesn't model. Render a muted note (matching
    // UnknownMessageBlock's pattern) so the user can tell content was dropped instead of
    // the part vanishing silently — important when a new server release adds a part type.
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.unknown_part),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun ReasoningBlock(text: String, streaming: Boolean, keyId: String, modifier: Modifier) {
    if (text.isBlank()) return
    val context = LocalContext.current
    // Key the expanded state on the part's stable id so a new ReasoningPart doesn't
    // inherit the positional saveable key of a prior block. A content-prefix key would
    // change on nearly every token while streaming and snap the block back to collapsed;
    // fall back to a content prefix only when the part has no id.
    val saveableKey = keyId.ifBlank { text.take(64) }
    var expanded by rememberSaveable(saveableKey) { mutableStateOf(false) }
    val expandedState = stringResource(R.string.state_expanded)
    val collapsedState = stringResource(R.string.state_collapsed)
    val thinkingLabel = stringResource(R.string.thinking)
    val thoughtsLabel = stringResource(R.string.thoughts)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 4.dp)
                .semantics {
                    stateDescription = if (expanded) expandedState else collapsedState
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (streaming) {
                CircularProgressIndicator(
                    Modifier.size(14.dp).semantics { contentDescription = thinkingLabel },
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
                if (streaming) thinkingLabel else thoughtsLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                // Wrap in SelectionContainer so the user can select a portion of the
                // reasoning (e.g. a single step) instead of the all-or-nothing copy
                // button below. Matches the markdown text's selectability.
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { copyToClipboard(context, "reasoning", text) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.copy), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Composable
private fun ToolCallView(part: ToolPart, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
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
        // A human-readable summary of what the tool was asked to do (e.g. "Reading
        // src/main.kt"), when the server provides one. Sits directly under the tool
        // name so the user understands the call before diving into input/output.
        part.state.titleText()?.takeIf { it.isNotBlank() }?.let { title ->
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp),
            )
        }
        // The tool's arguments (pretty-printed JSON), collapsible. Rendered before the
        // output so the call reads top-to-bottom: name → what → input → result.
        part.state.inputJson()?.let { input ->
            val inputLabel = stringResource(R.string.tool_input)
            val pretty = remember(input) {
                runCatching { prettyJson.encodeToString(JsonElement.serializer(), input) }.getOrDefault(input.toString())
            }
            CollapsibleDetail(
                label = inputLabel,
                detail = pretty,
                isDiff = false,
                keySuffix = "input",
            )
        }
        val detail = when (val s = part.state) {
            is ToolCompleted -> s.output?.takeIf { it.isNotBlank() }
            is ToolError -> s.error ?: stringResource(R.string.error_generic)
            else -> null
        }
        if (detail != null) {
            val collapsed = remember(detail) { detail.take(COLLAPSED_LIMIT) }
            var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
            val expandedState = stringResource(R.string.state_expanded)
            val collapsedState = stringResource(R.string.state_collapsed)
            val display = if (expanded || detail.length <= COLLAPSED_LIMIT) detail else collapsed
            // looksLikeDiff scans the whole string; memoize so a recomposition that doesn't
            // change `display` (e.g. an unrelated state flip) doesn't re-scan on every pass.
            val isDiff = remember(display) { looksLikeDiff(display) }
            CollapsibleDetail(
                label = null,
                detail = display,
                isDiff = isDiff,
                keySuffix = "output",
                expanded = expanded || detail.length <= COLLAPSED_LIMIT,
                onToggleExpand = if (detail.length > COLLAPSED_LIMIT) {
                    { expanded = !expanded }
                } else null,
                expandedState = expandedState,
                collapsedState = collapsedState,
                onCopy = { copyToClipboard(context, "output", detail) },
            )
        }
    }
}

/**
 * A collapsible monospace detail block shared by tool input and output. [label] is an
 * optional heading (e.g. "Input"); when null, no heading row is drawn (used for output
 * which follows the tool name directly). Long content is truncated to [COLLAPSED_LIMIT]
 * with an expand/collapse affordance when [onToggleExpand] is supplied.
 */
@Composable
private fun CollapsibleDetail(
    label: String?,
    detail: String,
    isDiff: Boolean,
    keySuffix: String,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    expandedState: String = "",
    collapsedState: String = "",
    onCopy: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        if (label != null) {
            var inputExpanded by rememberSaveable(label + keySuffix) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button) { inputExpanded = !inputExpanded }
                    .semantics { stateDescription = if (inputExpanded) expandedState else collapsedState },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (inputExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (!inputExpanded) return@Column
        }
        if (isDiff) {
            DiffView(detail)
        } else {
            // SelectionContainer so the user can select a portion of the output (e.g.
            // a single line of stdout) instead of only copy-all via the button below.
            SelectionContainer {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (onToggleExpand != null) {
            TextButton(
                onClick = onToggleExpand,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                modifier = Modifier.semantics { stateDescription = if (expanded) expandedState else collapsedState },
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
        if (onCopy != null) {
            TextButton(
                onClick = onCopy,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {},
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
        is ToolPending, is ToolRunning -> {
            val label = stringResource(R.string.tool_running)
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp).semantics { contentDescription = label },
                strokeWidth = 2.dp,
            )
        }
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
            Icon(
                Icons.Filled.Bolt,
                contentDescription = stringResource(R.string.tool_unknown),
                modifier = Modifier.size(16.dp),
            )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileChip(part: FilePart, modifier: Modifier, onOpenFile: ((String) -> Unit)?) {
    val context = LocalContext.current
    val path = part.source ?: part.url ?: part.filename
    val copyLabel = stringResource(R.string.copy_path)
    val openLabel = stringResource(R.string.open_file)
    // When a source path is available and a navigation callback is wired, tapping the
    // chip opens the file in the viewer (the action a user tapping a file reference
    // most likely expects). Long-press still copies the path to the clipboard, so the
    // copy affordance is preserved without being the default tap action. When no
    // navigation is wired (e.g. tests), falls back to copy-path on tap.
    val source = part.source
    val opener = onOpenFile
    val openPath: String? = if (opener != null && !source.isNullOrBlank()) source else null
    val canOpen = openPath != null
    val semanticsLabel = if (canOpen) openLabel else copyLabel
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                role = Role.Button,
                onClick = {
                    val toOpen = openPath
                    if (toOpen != null) {
                        opener?.invoke(toOpen)
                    } else if (!path.isNullOrBlank()) {
                        copyToClipboard(context, "path", path)
                        showToast(context, context.getString(R.string.path_copied))
                    }
                },
                onLongClick = {
                    if (!path.isNullOrBlank()) {
                        copyToClipboard(context, "path", path)
                        showToast(context, context.getString(R.string.path_copied))
                    }
                },
            )
            .semantics { contentDescription = semanticsLabel }
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
