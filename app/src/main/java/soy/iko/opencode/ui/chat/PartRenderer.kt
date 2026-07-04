package soy.iko.opencode.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.pluralStringResource
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
import soy.iko.opencode.data.model.TODO_WRITE_TOOL
import soy.iko.opencode.data.model.UnknownPart
import soy.iko.opencode.data.model.inputElement
import soy.iko.opencode.data.model.parseTodos
import soy.iko.opencode.data.model.sourcePath
import soy.iko.opencode.ui.components.DiffView
import soy.iko.opencode.ui.components.ImageLoadContext
import soy.iko.opencode.ui.components.MarkdownText
import soy.iko.opencode.ui.components.RemoteImage
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.isImage
import soy.iko.opencode.ui.components.looksLikeDiff
import soy.iko.opencode.ui.components.rememberVisibilityTransitions
import soy.iko.opencode.ui.components.showCopyToast
import soy.iko.opencode.R

private const val COLLAPSED_LIMIT = 4000

// Pretty-printer for tool inputs. Separate from OpencodeJson (which is tuned for
// resilient decoding) so this stays human-readable; constructed lazily and memoized.
private val prettyJson: Json = Json { prettyPrint = true }

/** Extract a human-readable title (e.g. "Reading src/main.kt") from a tool state. */
private fun ToolState.titleText(): String? = when (this) {
    is ToolRunning -> title
    is ToolCompleted -> title
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
    // when the part has no id, use a constant — the key(part.id, index) wrapper at the
    // call site already disambiguates sibling parts positionally, so a constant per
    // block type won't collide.
    val saveableKey = keyId.ifBlank { "reasoning" }
    val haptics = LocalHapticFeedback.current
    // While the reasoning is still streaming, default to expanded so live "Thinking…" is
    // visible; once complete it collapses. A non-null override records an explicit user
    // toggle and wins over the streaming default, so we don't fight a user who collapsed it.
    var userOverride by rememberSaveable(saveableKey) { mutableStateOf<Boolean?>(null) }
    // Sticky expand: once a block has streamed this session, pin it open after streaming ends
    // (until the user explicitly collapses it) so it isn't yanked away mid-read the instant the
    // stream finishes. A historical block that never streamed this session (streaming is false
    // from the first composition) never sets streamedThisSession, so it stays collapsed.
    var streamedThisSession by remember(saveableKey) { mutableStateOf(false) }
    LaunchedEffect(streaming) {
        if (streaming) streamedThisSession = true
        else if (streamedThisSession && userOverride == null) userOverride = true
    }
    val expanded = userOverride ?: streaming
    val expandedState = stringResource(R.string.state_expanded)
    val collapsedState = stringResource(R.string.state_collapsed)
    val thinkingLabel = stringResource(R.string.thinking)
    val thoughtsLabel = stringResource(R.string.thoughts)
    // Hoist the collapsed word count so it can drive both the visible "(N)" label and the
    // row's a11y stateDescription. Previously the count rendered as a bare "(12)" that
    // TalkBack read as "12" with no context; folding it into the stateDescription makes the
    // hidden-content size reachable to screen-reader users.
    val collapsedWordCount = remember(text) {
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }
    // Resolve the readable "N words hidden" form once (in the composable body — the semantics
    // lambda below is not a composable scope, so it can't call pluralStringResource itself).
    val collapsedWordsLabel = pluralStringResource(
        R.plurals.reasoning_word_count,
        collapsedWordCount,
        collapsedWordCount,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(role = Role.Button) { userOverride = !expanded }
                .padding(vertical = 4.dp)
                .semantics {
                    // When collapsed (and not streaming), append how much reasoning is hidden
                    // so the state reads e.g. "Collapsed, 42 words hidden" — giving a TalkBack
                    // user the same size signal the visible "(42)" gives a sighted user.
                    stateDescription = when {
                        expanded -> expandedState
                        streaming -> collapsedState
                        else -> "$collapsedState, $collapsedWordsLabel"
                    }
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
            // When collapsed, show a muted word count so the user has a signal of how much
            // content is hidden before deciding to expand. Skipped while streaming (the
            // content is growing and the label already says "Thinking…").
            if (!streaming && !expanded) {
                Text(
                    "($collapsedWordCount)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        val expandMotion = rememberVisibilityTransitions()
        AnimatedVisibility(
            visible = expanded,
            enter = expandMotion.enter,
            exit = expandMotion.exit,
        ) {
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
            }
        }
        // Copy affordance lives outside AnimatedVisibility so it's reachable when the
        // block is collapsed — previously the copy button was inside the expanded content,
        // leaving a collapsed reasoning block with no copy affordance at all.
        TextButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                copyToClipboard(context, context.getString(R.string.clip_label_reasoning), text)
            },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.copy), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Composable
private fun ToolCallView(part: ToolPart, modifier: Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
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
        // A todowrite call carries the agent's task plan as its input; render it as a
        // checklist rather than raw JSON so each step's progress reads at a glance.
        val planTodos = remember(part) {
            if (part.tool.equals(TODO_WRITE_TOOL, ignoreCase = true)) parseTodos(part.state.inputElement()) else emptyList()
        }
        if (planTodos.isNotEmpty()) {
            TodoPlanChecklist(planTodos, modifier = Modifier.padding(top = 8.dp))
        } else {
            // The tool's arguments (pretty-printed JSON), collapsible. Rendered before the
            // output so the call reads top-to-bottom: name → what → input → result.
            part.state.inputElement()?.let { input ->
                val inputLabel = stringResource(R.string.tool_input)
                val pretty = remember(input) {
                    runCatching { prettyJson.encodeToString(JsonElement.serializer(), input) }.getOrDefault(input.toString())
                }
                CollapsibleDetail(
                    label = inputLabel,
                    detail = pretty,
                    isDiff = false,
                    keySuffix = "input",
                    // The input block has a label row whose collapse toggle announces its state
                    // via stateDescription; pass the strings so TalkBack reads "expanded"/"collapsed"
                    // rather than an empty state (the defaults are "").
                    expandedState = stringResource(R.string.state_expanded),
                    collapsedState = stringResource(R.string.state_collapsed),
                    // Mirror the output block's copy affordance so a tool's arguments (e.g. a
                    // bash command or file-write content) can be copied for reuse without manual
                    // text selection.
                    onCopy = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        copyToClipboard(context, context.getString(R.string.clip_label_input), pretty)
                    },
                )
            }
        }
        val detail = when (val s = part.state) {
            is ToolCompleted -> s.output?.takeIf { it.isNotBlank() }
            is ToolError -> s.error ?: stringResource(R.string.error_generic)
            else -> null
        }
        if (detail != null) {
            // looksLikeDiff scans the whole string; memoize on `detail` so a recomposition that
            // doesn't change the content (e.g. the expand/collapse flip) doesn't re-scan.
            val isDiff = remember(detail) { looksLikeDiff(detail) }
            val collapsed = remember(detail, isDiff) {
                val head = detail.take(COLLAPSED_LIMIT)
                // Truncating a diff mid-line makes DiffView render a malformed final line, so
                // trim back to the last complete line when the content is a diff.
                if (isDiff) head.substringBeforeLast('\n') else head
            }
            var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
            val expandedState = stringResource(R.string.state_expanded)
            val collapsedState = stringResource(R.string.state_collapsed)
            val display = if (expanded || detail.length <= COLLAPSED_LIMIT) detail else collapsed
            // How many lines are hidden while collapsed. detail.lines() splits the whole
            // (potentially multi-KB) output, so memoize it — otherwise it re-splits on
            // every recomposition, including each streaming update of a running tool.
            // `collapsed` is a prefix of `detail`, so its line count never exceeds it;
            // 0 (a single long line cut mid-way) falls back to the generic label.
            val moreLines = remember(detail, collapsed) {
                if (detail.length > COLLAPSED_LIMIT) detail.lines().size - collapsed.lines().size else 0
            }
            CollapsibleDetail(
                label = null,
                detail = display,
                isDiff = isDiff,
                keySuffix = "output",
                expanded = expanded || detail.length <= COLLAPSED_LIMIT,
                onToggleExpand = if (detail.length > COLLAPSED_LIMIT) {
                    { expanded = !expanded }
                } else null,
                moreLines = moreLines,
                expandedState = expandedState,
                collapsedState = collapsedState,
                onCopy = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    copyToClipboard(context, context.getString(R.string.clip_label_output), detail)
                },
                diffSaveKey = part.id,
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
    moreLines: Int = 0,
    expandedState: String = "",
    collapsedState: String = "",
    onCopy: (() -> Unit)? = null,
    diffSaveKey: String? = null,
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
            DiffView(detail, saveKey = diffSaveKey)
        } else {
            // SelectionContainer so the user can select a portion of the output (e.g.
            // a single line of stdout) instead of only copy-all via the button below.
            // Horizontal scroll + softWrap=false so wide stdout / tables scroll instead of
            // soft-wrapping into an unreadable ragged block (mirrors the file viewer).
            SelectionContainer {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
        if (onToggleExpand != null) {
            TextButton(
                onClick = onToggleExpand,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { stateDescription = if (expanded) expandedState else collapsedState },
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    when {
                        expanded -> stringResource(R.string.show_less)
                        moreLines > 0 -> pluralStringResource(R.plurals.show_more_lines, moreLines, moreLines)
                        else -> stringResource(R.string.show_more)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (onCopy != null) {
            TextButton(
                onClick = onCopy,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics(mergeDescendants = true) {},
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
        is ToolPending -> {
            // Queued, not yet executing: a static clock icon distinguishes it from an
            // actively-running tool (spinner) so the user can tell what's waiting.
            val label = stringResource(R.string.tool_queued)
            Icon(
                Icons.Filled.Schedule,
                contentDescription = label,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ToolRunning -> {
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
    val haptics = LocalHapticFeedback.current
    val path = part.sourcePath ?: part.url ?: part.filename
    val copyLabel = stringResource(R.string.copy_path)
    val openLabel = stringResource(R.string.open_file)
    // When a source path is available and a navigation callback is wired, tapping the
    // chip opens the file in the viewer (the action a user tapping a file reference
    // most likely expects). Long-press still copies the path to the clipboard, so the
    // copy affordance is preserved without being the default tap action. When no
    // navigation is wired (e.g. tests), falls back to copy-path on tap.
    val source = part.sourcePath
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
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        copyToClipboard(context, context.getString(R.string.clip_label_path), path)
                        showCopyToast(context, context.getString(R.string.path_copied))
                    }
                },
                onLongClick = {
                    if (!path.isNullOrBlank()) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        copyToClipboard(context, context.getString(R.string.clip_label_path), path)
                        showCopyToast(context, context.getString(R.string.path_copied))
                    }
                },
            )
            .semantics { contentDescription = semanticsLabel }
            .defaultMinSize(minHeight = 48.dp)
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
