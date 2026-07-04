package soy.iko.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import soy.iko.opencode.R
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelOption
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.Tokens
import soy.iko.opencode.data.model.UnknownMessage
import soy.iko.opencode.data.model.UserMessage
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.ui.components.ImageLoadContext
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.showToast
import soy.iko.opencode.ui.components.RelativeTimeText

/**
 * Resolve an assistant message's model id to a friendly label from the loaded catalog,
 * falling back to the raw id (so a model not in the catalog still shows something
 * meaningful instead of being hidden). Returns null only when the message has no model.
 */
fun resolveModelLabel(info: AssistantMessage, models: List<ModelOption>): String? {
    val id = info.modelID ?: return null
    if (models.isEmpty()) return id
    val byPair = models.firstOrNull { it.providerID == info.providerID && it.modelID == id }
    return byPair?.modelLabel
        ?: models.firstOrNull { it.modelID == id }?.modelLabel
        ?: id
}

/** Status of an outgoing message, shown as a small indicator on the user bubble. */
enum class MessageSendStatus { SENDING, FAILED }

/** A single message: user prompts right-aligned in a bubble, assistant output full-width. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageWithParts,
    modifier: Modifier = Modifier,
    isRunning: Boolean = false,
    imageContext: ImageLoadContext? = null,
    modelLabel: String? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
    onSpeak: ((String) -> Unit)? = null,
    isSpeaking: Boolean = false,
    onQuote: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    sendStatus: MessageSendStatus? = null,
    isEdited: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    when (message.info) {
        is UserMessage -> UserBubble(message, imageContext, modifier, onOpenFile, onRevert, onEdit, onQuote, onBranch, sendStatus, isEdited, onRetry, onDismiss)
        is UnknownMessage -> UnknownMessageBlock(message, imageContext, modifier, onOpenFile)
        else -> AssistantBlock(message, isRunning, imageContext, modifier, modelLabel, onOpenFile, onRevert, onSpeak, isSpeaking, onQuote, onBranch, onRegenerate, isEdited)
    }
}

/** A long-press context menu consolidating all per-message actions (copy, quote, branch,
 *  revert). Surfacing these via long-press matches the conventional Android pattern so the
 *  actions are discoverable without spotting the small 18dp inline icon row. The menu is
 *  anchored to the long-press position via [offset], and only items with non-null callbacks
 *  (and text to act on) are shown. */
@Composable
private fun MessageLongPressMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    offset: androidx.compose.ui.unit.DpOffset,
    text: String?,
    onCopy: (() -> Unit)?,
    onQuote: ((String) -> Unit)?,
    onBranch: ((String) -> Unit)?,
    onRevert: (() -> Unit)?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        if (text != null && onCopy != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                text = { Text(stringResource(R.string.copy)) },
                onClick = { onDismiss(); onCopy() },
            )
        }
        if (text != null && onQuote != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null) },
                text = { Text(stringResource(R.string.quote_reply)) },
                onClick = { onDismiss(); onQuote(text) },
            )
        }
        if (text != null && onBranch != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.branch_session)) },
                onClick = { onDismiss(); onBranch(text) },
            )
        }
        if (onRevert != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Restore, contentDescription = null) },
                text = { Text(stringResource(R.string.revert_to_here)) },
                onClick = { onDismiss(); onRevert() },
            )
        }
    }
}

/** Overflow menu of secondary per-message actions (quote-reply, branch-a-new-session).
 *  Shown when at least one action is available and there's text to act on. Keeps the
 *  inline action row (copy/edit/revert/speak) uncluttered while still surfacing the extras. */
@Composable
private fun MessageOverflow(
    text: String,
    onQuote: ((String) -> Unit)?,
    onBranch: ((String) -> Unit)?,
) {
    if (onQuote == null && onBranch == null) return
    var expanded by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.message_actions)
    val haptics = LocalHapticFeedback.current
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = moreLabel,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            onQuote?.let { quote ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null) },
                    text = { Text(stringResource(R.string.quote_reply)) },
                    onClick = {
                        expanded = false
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        quote(text)
                    },
                )
            }
            onBranch?.let { branch ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                    text = { Text(stringResource(R.string.branch_session)) },
                    onClick = {
                        expanded = false
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        branch(text)
                    },
                )
            }
        }
    }
}

/** A small "revert to before this message" icon button, shown next to Copy when a revert
 *  handler is supplied. Reverting is undoable (see the chat's revert banner), so it acts
 *  immediately without a confirmation dialog. */
@Composable
private fun RevertButton(onRevert: () -> Unit) {
    val label = stringResource(R.string.revert_to_here)
    val haptics = LocalHapticFeedback.current
    IconButton(onClick = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onRevert()
    }) {
        Icon(
            Icons.Filled.Restore,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnknownMessageBlock(
    message: MessageWithParts,
    imageContext: ImageLoadContext?,
    modifier: Modifier,
    onOpenFile: ((String) -> Unit)? = null,
) {
    // Forward-compat: a role the client doesn't model. Render a muted note so the user
    // sees *something* rather than an unlabeled block, plus any parts (e.g. text) the
    // server attached, so content isn't silently dropped.
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.unknown_message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        // key() per part so each PartView gets a distinct saveable registry slot: rememberSaveable
        // inside PartView keys its RESET on part.id but its registry key is the positional
        // compositeKeyHash, which collides for sibling parts in a keyless loop — mis-restoring
        // expand/collapse state across parts after process death. (index disambiguates blank ids.)
        message.parts.forEachIndexed { index, part ->
            key(part.id, index) {
                PartView(part, imageContext = imageContext, onOpenFile = onOpenFile)
            }
        }
        MessageTimestampText(message.info)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: MessageWithParts,
    imageContext: ImageLoadContext?,
    modifier: Modifier,
    onOpenFile: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
    onQuote: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    sendStatus: MessageSendStatus? = null,
    isEdited: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val copyLabel = stringResource(R.string.copy)
    val editLabel = stringResource(R.string.edit_message)
    // Long-press context menu state: consolidates copy/quote/branch/revert into a standard
    // Android long-press menu so the actions are discoverable without spotting the 18dp icons.
    // The menu anchors at the actual touch point rather than a fixed corner.
    var longPressMenu by remember { mutableStateOf(false) }
    var longPressOffset by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset(0.dp, 0.dp)) }
    // Collect text from all TextParts for copying, so a user can reuse/repost their
    // own prompt. Memoized so a scroll-induced recomposition doesn't re-scan the list.
    val textToCopy = remember(message.parts) {
        message.parts
            .filterIsInstance<TextPart>()
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotBlank() }
    }
    val onCopy: (() -> Unit)? = textToCopy?.let { text ->
        {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            copyToClipboard(context, context.getString(R.string.clip_label_message), text)
        }
    }
    // Wrap-content bubble capped at the width fraction (end-aligned), so a one-word prompt is
    // snug instead of a wide empty box while long prompts still cap at the readable fraction.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .widthIn(max = maxWidth * NetworkConfig.userBubbleWidthFraction)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // key() per part so each PartView gets its own saveable registry slot (see AssistantBlock).
            message.parts.forEachIndexed { index, part ->
                key(part.id, index) { PartView(part, imageContext = imageContext, onOpenFile = onOpenFile) }
            }
            // Footer row with timestamp + inline actions. Long-press opens a context menu
            // consolidating copy/quote/branch/revert — the conventional Android pattern — so
            // the actions are discoverable without spotting the 18dp inline icons. Uses
            // pointerInput/detectTapGestures (not combinedClickable) so no misleading "Button"
            // semantics are announced to TalkBack for an area whose tap does nothing, and the
            // menu anchors at the actual long-press position instead of a fixed corner.
            Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { offset ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            longPressOffset = androidx.compose.ui.unit.DpOffset(
                                with(density) { offset.x.toDp() },
                                with(density) { offset.y.toDp() },
                            )
                            longPressMenu = true
                        })
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (sendStatus) {
                        MessageSendStatus.SENDING -> {
                            CircularProgressIndicator(
                                Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.message_sending),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MessageSendStatus.FAILED -> {
                            // Tap-to-retry: the whole failed label is tappable to re-send (far more
                            // discoverable than relying on the transient snackbar). A dismiss (×) button
                            // removes the abandoned message entirely, since its text will never match a
                            // real server message and would otherwise linger forever.
                            val retryModifier = if (onRetry != null) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(),
                                    role = Role.Button,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onRetry()
                                    },
                                ).padding(vertical = 2.dp)
                            } else {
                                Modifier.padding(vertical = 2.dp)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = retryModifier.semantics(mergeDescendants = true) {
                                    contentDescription = context.getString(R.string.message_send_failed) +
                                        ". " + context.getString(R.string.tap_to_retry)
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Error,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    stringResource(R.string.message_send_failed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            if (onDismiss != null) {
                                // No size override on the IconButton: it keeps the default 48dp
                                // touch target (only the inner Icon is shrunk to 14dp). An earlier
                                // `.size(20.dp)` here clamped the target to 20dp — well under the
                                // M3 minimum — which made the discard action hard to hit. Matches
                                // the pattern used by every other inline IconButton in this file.
                                IconButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onDismiss()
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.outbox_discard),
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        null -> {}
                    }
                    if (isEdited && sendStatus == null) {
                        Text(
                            stringResource(R.string.message_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MessageTimestampText(message.info)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Edit affordance: reload this prompt into the composer, reverting the
                    // conversation to before it so a Send replaces it. Only when there's text
                    // to edit (an image-only prompt has nothing to reload).
                    if (onEdit != null && textToCopy != null) {
                        val dropsAttachmentsMsg = stringResource(R.string.edit_drops_attachments)
                        val hasAttachments = message.parts.any { it is FilePart }
                        IconButton(onClick = {
                            onEdit(textToCopy)
                            // Editing reloads only the text; if this prompt carried images or
                            // files, tell the user they won't be reattached rather than
                            // silently dropping them.
                            if (hasAttachments) showToast(context, dropsAttachmentsMsg)
                        }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = editLabel,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    onRevert?.let { RevertButton(it) }
                    // Copy affordance for user prompts, mirroring the assistant block.
                    // Without it, reusing a prior prompt requires discovering the
                    // long-press on the markdown text (and only TextParts support that).
                    if (textToCopy != null) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                copyToClipboard(context, context.getString(R.string.clip_label_message), textToCopy)
                            },
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = copyLabel,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Quote-reply / branch-a-new-session, tucked behind an overflow so the
                    // inline row stays compact. Only when there's prompt text to act on.
                    if (textToCopy != null) {
                        MessageOverflow(text = textToCopy, onQuote = onQuote, onBranch = onBranch)
                    }
                }
            }
            MessageLongPressMenu(
                expanded = longPressMenu,
                onDismiss = { longPressMenu = false },
                offset = longPressOffset,
                text = textToCopy,
                onCopy = onCopy,
                onQuote = onQuote,
                onBranch = onBranch,
                onRevert = onRevert,
            )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AssistantBlock(
    message: MessageWithParts,
    isRunning: Boolean,
    imageContext: ImageLoadContext?,
    modifier: Modifier,
    modelLabel: String? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onSpeak: ((String) -> Unit)? = null,
    isSpeaking: Boolean = false,
    onQuote: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    isEdited: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val info = message.info
        if (info is AssistantMessage) {
            val label = modelLabel ?: info.modelID
            // Avatar + model label row: the robot icon gives the assistant a consistent visual
            // identity (left-aligned, mirroring the user's right-aligned bubble) so on a long
            // scroll the speaker is unambiguous without reading the label.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (label != null) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        message.parts.forEachIndexed { index, part ->
            // Only the final part of a running message is actively streaming; earlier parts
            // (a finished reasoning block, a completed tool call) are already done, so passing
            // isRunning to all of them would keep a completed reasoning block showing the
            // "Thinking…" spinner until the whole message finishes.
            val partStreaming = isRunning && index == message.parts.lastIndex
            // key() per part so each PartView gets a distinct saveable registry slot: without it,
            // rememberSaveable expand/collapse state (keyed only positionally) is shared across
            // sibling parts and mis-restored after process death. (index disambiguates blank ids.)
            key(part.id, index) {
                PartView(part, isRunning = partStreaming, modifier = Modifier.fillMaxWidth(), imageContext = imageContext, onOpenFile = onOpenFile)
            }
        }
        if (info is AssistantMessage) {
            val cost = info.cost
            val tokens = info.tokens
            val tokenFormat = stringResource(R.string.tokens_in_out)
            val costShort = stringResource(R.string.cost_format_short)
            val costLong = stringResource(R.string.cost_format_long)
            // Memoize the formatted cost/tokens line so a scroll-induced or unrelated
            // state-flip recomposition doesn't re-run NumberFormat + buildList +
            // joinToString for every visible assistant bubble.
            val costSummary = remember(info.isComplete, tokens, cost, tokenFormat, costShort, costLong) {
                if (!info.isComplete || (cost == null && tokens == null)) null
                else buildList {
                    // Skip an all-zero token count (e.g. a completed message that reported no
                    // usage) so the bubble doesn't show a meaningless "0 in · 0 out".
                    tokens?.takeIf { it.input > 0 || it.output > 0 }?.let { add(formatTokens(it, tokenFormat)) }
                    cost?.takeIf { it > 0 }?.let { add(formatCost(it, costShort, costLong)) }
                }.takeIf { it.isNotEmpty() }?.joinToString("  •  ")
            }
            // Long-press context menu state (see UserBubble for rationale).
            var longPressMenu by remember { mutableStateOf(false) }
            var longPressOffset by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset(0.dp, 0.dp)) }
            // Collect text from all TextParts for copy/read-aloud. Memoized so a
            // scroll-induced recomposition doesn't re-scan the parts list.
            val textToCopy = remember(message.parts) {
                message.parts
                    .filterIsInstance<TextPart>()
                    .joinToString("\n\n") { it.text }
                    .takeIf { it.isNotBlank() }
            }
            val assistantHaptics = LocalHapticFeedback.current
            val assistantContext = LocalContext.current
            val assistantDensity = androidx.compose.ui.platform.LocalDensity.current
            val onCopy: (() -> Unit)? = textToCopy?.let { text ->
                {
                    assistantHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    copyToClipboard(assistantContext, assistantContext.getString(R.string.clip_label_message), text)
                }
            }
            Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { offset ->
                            assistantHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            longPressOffset = androidx.compose.ui.unit.DpOffset(
                                with(assistantDensity) { offset.x.toDp() },
                                with(assistantDensity) { offset.y.toDp() },
                            )
                            longPressMenu = true
                        })
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    costSummary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isEdited) {
                        Text(
                            stringResource(R.string.message_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MessageTimestampText(message.info)
                }
                AssistantActions(
                    textToCopy = textToCopy,
                    isSpeaking = isSpeaking,
                    onSpeak = onSpeak,
                    onRevert = onRevert,
                    onQuote = onQuote,
                    onBranch = onBranch,
                    onRegenerate = onRegenerate,
                )
            }
            MessageLongPressMenu(
                expanded = longPressMenu,
                onDismiss = { longPressMenu = false },
                offset = longPressOffset,
                text = textToCopy,
                onCopy = onCopy,
                onQuote = onQuote,
                onBranch = onBranch,
                onRevert = onRevert,
            )
            }
        } else {
            MessageTimestampText(message.info)
        }
    }
}

/** Trailing action buttons for an assistant message: read-aloud (TTS), revert, copy.
 *  Extracted from [AssistantBlock] to keep that function under the complexity threshold. */
@Composable
private fun AssistantActions(
    textToCopy: String?,
    isSpeaking: Boolean,
    onSpeak: ((String) -> Unit)?,
    onRevert: (() -> Unit)?,
    onQuote: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val copyLabel = stringResource(R.string.copy)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Regenerate: re-run the preceding user prompt to get a fresh reply. Only meaningful
        // for a completed, non-streaming message, so the caller gates it on !isRunning.
        if (onRegenerate != null) {
            val regenerateLabel = stringResource(R.string.regenerate)
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onRegenerate()
            }) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = regenerateLabel,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Read-aloud toggle: speaks the assistant text via TextToSpeech, showing a Stop
        // icon while this message is the one being spoken.
        if (onSpeak != null && textToCopy != null) {
            val speakLabel = stringResource(if (isSpeaking) R.string.stop_reading else R.string.read_aloud)
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSpeak(textToCopy)
            }) {
                Icon(
                    if (isSpeaking) Icons.Filled.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = speakLabel,
                    modifier = Modifier.size(18.dp),
                    tint = if (isSpeaking) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        onRevert?.let { RevertButton(it) }
        if (textToCopy != null) {
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                copyToClipboard(context, context.getString(R.string.clip_label_message), textToCopy)
            }) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = copyLabel,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Assistant replies can be quoted into the composer or branched into a new session.
        if (textToCopy != null && (onQuote != null || onBranch != null)) {
            MessageOverflow(text = textToCopy, onQuote = onQuote, onBranch = onBranch)
        }
    }
}

@Composable
private fun MessageTimestampText(info: soy.iko.opencode.data.model.MessageInfo) {
    // Compact relative label by default; long-press reveals the full absolute timestamp
    // (RelativeTimeText renders nothing when the time is absent, so no isNotEmpty guard).
    RelativeTimeText(info.time?.created)
}

// NumberFormat.getNumberInstance performs an expensive ICU locale lookup + object
// construction on every call. Reuse a thread-local instance so repeated calls (e.g.
// when the message list re-seeds after a reconnect and every visible assistant bubble
// recomposes at once) don't each pay that cost. Thread-local because NumberFormat is
// not thread-safe.
private val tokenNumberFormat: ThreadLocal<java.text.NumberFormat> = ThreadLocal.withInitial {
    java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
}

private fun formatTokens(tokens: Tokens, format: String): String {
    val nf = tokenNumberFormat.get()!!
    return format.format(nf.format(tokens.input), nf.format(tokens.output))
}

private fun formatCost(cost: Double, shortFormat: String, longFormat: String): String =
    // Locale.US so the formatting is stable regardless of device locale (avoids
    // non-ASCII digits or comma decimal separators in a dollar amount). The format
    // string itself is localized via strings.xml so the currency symbol can be
    // adapted by translators.
    if (cost < 0.01) String.format(java.util.Locale.US, longFormat, cost)
    else String.format(java.util.Locale.US, shortFormat, cost)
