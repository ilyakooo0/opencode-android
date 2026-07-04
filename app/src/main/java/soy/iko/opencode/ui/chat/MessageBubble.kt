package soy.iko.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import soy.iko.opencode.R
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelOption
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.TextPart
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
    agentLabel: String? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
    onSpeak: ((String) -> Unit)? = null,
    isSpeaking: Boolean = false,
    ttsState: TtsState = TtsState.IDLE,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onQuote: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    sendStatus: MessageSendStatus? = null,
    isEdited: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    // Whether this is the first message in a consecutive run of the same speaker. When false,
    // the assistant avatar/header is suppressed to reduce visual noise in tool-heavy runs.
    isFirstOfSpeaker: Boolean = true,
    // When true, the bubble gets a transient highlight background (used by the global-search
    // deep link to mark the matched message after scrolling it into view).
    highlighted: Boolean = false,
) {
    when (message.info) {
        is UserMessage -> UserBubble(message, imageContext, modifier, onOpenFile, onRevert, onEdit, onQuote, onBranch, sendStatus, isEdited, onRetry, onDismiss, onShare)
        is UnknownMessage -> UnknownMessageBlock(message, imageContext, modifier, onOpenFile)
        else -> AssistantBlock(message, isRunning, imageContext, modifier, modelLabel, agentLabel, onOpenFile, onRevert, onSpeak, isSpeaking, ttsState, onPause, onResume, onStop, onQuote, onBranch, onRegenerate, onContinue, isEdited, onShare, isFirstOfSpeaker, highlighted)
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
    onShare: (() -> Unit)?,
    onEdit: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
    speakLabel: String? = null,
    onViewSource: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        if (text != null && onCopy != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                text = { Text(stringResource(R.string.copy)) },
                onClick = { onDismiss(); onCopy() },
            )
        }
        // Per-message share: fire an ACTION_SEND with this message's Markdown, so a user can
        // forward a single reply (e.g. a code snippet) without exporting the whole transcript.
        // Gated on text so an image-only prompt doesn't show a no-op Share entry.
        if (text != null && onShare != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                text = { Text(stringResource(R.string.share_message)) },
                onClick = { onDismiss(); onShare() },
            )
        }
        // Edit (user prompts): reload the prompt into the composer. Surfaced here as well as
        // inline so the action is discoverable without spotting the 18dp icon.
        if (text != null && onEdit != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                text = { Text(stringResource(R.string.edit_message)) },
                onClick = { onDismiss(); onEdit() },
            )
        }
        // Regenerate (assistant): re-run the preceding user prompt. A flagship action that was
        // previously only reachable via the tiny inline icon.
        if (onRegenerate != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                text = { Text(stringResource(R.string.regenerate)) },
                onClick = { onDismiss(); onRegenerate() },
            )
        }
        // Read aloud / Stop reading (assistant): toggles TTS. The label is supplied by the caller
        // so it reflects the current speaking state (play/pause/resume).
        if (text != null && onSpeak != null && speakLabel != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                text = { Text(speakLabel) },
                onClick = { onDismiss(); onSpeak() },
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
        // View source: show the raw markdown of the message's TextParts in a dialog, so a user can
        // inspect malformed tables/rendering or copy the exact source. Gated on text so an
        // image-only message doesn't show a no-op entry.
        if (text != null && onViewSource != null) {
            DropdownMenuItem(
                leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                text = { Text(stringResource(R.string.view_source)) },
                onClick = { onDismiss(); onViewSource() },
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

/** Wraps [MessageLongPressMenu] for a user prompt, building the Edit callback (which reloads
 *  the prompt into the composer and warns when the original carried attachments) so the
 *  branch count stays out of [UserBubble]. */
@Composable
private fun UserMessageLongPressMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    offset: androidx.compose.ui.unit.DpOffset,
    text: String?,
    onCopy: (() -> Unit)?,
    onQuote: ((String) -> Unit)?,
    onBranch: ((String) -> Unit)?,
    onRevert: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onEdit: ((String) -> Unit)?,
    hasAttachments: Boolean,
    dropsAttachmentsMsg: String,
    context: android.content.Context,
) {
    val editForMenu: (() -> Unit)? = if (onEdit != null && text != null) {
        { onEdit(text); if (hasAttachments) showToast(context, dropsAttachmentsMsg) }
    } else {
        null
    }
    MessageLongPressMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        offset = offset,
        text = text,
        onCopy = onCopy,
        onQuote = onQuote,
        onBranch = onBranch,
        onRevert = onRevert,
        onShare = onShare,
        onEdit = editForMenu,
    )
}

/** Overflow menu of secondary per-message actions (quote-reply, branch-a-new-session, view source).
 *  Shown when at least one action is available and there's text to act on. Keeps the
 *  inline action row (copy/edit/revert/speak) uncluttered while still surfacing the extras. */
@Composable
private fun MessageOverflow(
    text: String,
    onQuote: ((String) -> Unit)?,
    onBranch: ((String) -> Unit)?,
    onViewSource: (() -> Unit)? = null,
) {
    if (onQuote == null && onBranch == null && onViewSource == null) return
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
            onViewSource?.let { viewSource ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                    text = { Text(stringResource(R.string.view_source)) },
                    onClick = {
                        expanded = false
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewSource()
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
    // server attached, so content isn't silently dropped. A copy action is wired so a user
    // can at least extract the content (e.g. to report a new server role's payload).
    val context = androidx.compose.ui.platform.LocalContext.current
    val textToCopy = remember(message.parts) {
        message.parts.filterIsInstance<soy.iko.opencode.data.model.TextPart>()
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotBlank() }
    }
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
            if (textToCopy != null) {
                IconButton(onClick = {
                    copyToClipboard(context, context.getString(R.string.clip_label_message), textToCopy)
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.copy),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    onShare: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val copyLabel = stringResource(R.string.copy)
    val editLabel = stringResource(R.string.edit_message)
    val dropsAttachmentsMsg = stringResource(R.string.edit_drops_attachments)
    val hasAttachments = message.parts.any { it is FilePart }
    // A11y: announce the speaker role so a screen-reader user can tell user from
    // assistant bubbles without inferring from bubble position. Prefixed onto the
    // bubble's merged semantics so TalkBack reads "You, <message text>".
    val roleLabel = stringResource(R.string.role_user_message)
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
                // Merge descendants and prefix the role so TalkBack reads "You, <text>"
                // instead of just the message text — letting a screen-reader user tell
                // user from assistant bubbles without inferring from position.
                .semantics(mergeDescendants = true) { contentDescription = roleLabel }
                // Long-press anywhere on the bubble (body or footer) opens the context menu —
                // the conventional Android pattern — so the actions are discoverable without
                // spotting the 18dp inline icons. detectTapGestures with only onLongPress does
                // not consume taps, so text selection and inline-icon clicks still work; a long
                // press on selectable text is consumed by the SelectionContainer first, so the
                // menu only opens from non-text areas (the intended behavior).
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        longPressOffset = androidx.compose.ui.unit.DpOffset(
                            with(density) { offset.x.toDp() },
                            with(density) { offset.y.toDp() },
                        )
                        longPressMenu = true
                    })
                }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // key() per part so each PartView gets its own saveable registry slot (see AssistantBlock).
            message.parts.forEachIndexed { index, part ->
                key(part.id, index) { PartView(part, imageContext = imageContext, onOpenFile = onOpenFile) }
            }
            // Footer row with timestamp + inline actions.
            Box {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Crossfade the send-status slot so SENDING → FAILED → null transitions
                    // don't snap; a subtle fade reads as a polish detail rather than a pop.
                    androidx.compose.animation.AnimatedContent(
                        targetState = sendStatus,
                        transitionSpec = {
                            androidx.compose.animation.fadeIn() togetherWith
                                androidx.compose.animation.fadeOut()
                        },
                        label = "send_status",
                    ) { status ->
                        when (status) {
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
            UserMessageLongPressMenu(
                expanded = longPressMenu,
                onDismiss = { longPressMenu = false },
                offset = longPressOffset,
                text = textToCopy,
                onCopy = onCopy,
                onQuote = onQuote,
                onBranch = onBranch,
                onRevert = onRevert,
                onShare = onShare,
                onEdit = onEdit,
                hasAttachments = hasAttachments,
                dropsAttachmentsMsg = dropsAttachmentsMsg,
                context = context,
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
    agentLabel: String? = null,
    onOpenFile: ((String) -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    onSpeak: ((String) -> Unit)? = null,
    isSpeaking: Boolean = false,
    ttsState: TtsState = TtsState.IDLE,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    onQuote: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    isEdited: Boolean = false,
    onShare: (() -> Unit)? = null,
    isFirstOfSpeaker: Boolean = true,
    highlighted: Boolean = false,
) {
    // Long-press context menu state, hoisted to the block scope so the whole bubble (body and
    // footer) is the long-press target — see UserBubble for rationale.
    val assistantHaptics = LocalHapticFeedback.current
    val assistantContext = LocalContext.current
    val assistantDensity = androidx.compose.ui.platform.LocalDensity.current
    var longPressMenu by remember { mutableStateOf(false) }
    var longPressOffset by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset(0.dp, 0.dp)) }
    // View-source dialog state: shown from the long-press menu's "View source" entry to display
    // the raw markdown of the message's TextParts.
    var showSource by remember { mutableStateOf(false) }
    // A11y: announce the speaker role so TalkBack reads "Assistant, <text>" instead of
    // only the model/agent label — giving a screen-reader user an explicit role signal
    // matching the user bubble's "You" prefix.
    val assistantRoleLabel = stringResource(R.string.role_assistant_message)
    // Transient highlight background (from global-search deep link). Animated so the highlight
    // fades in/out rather than flashing, and so the background clears smoothly when the focus
    // is cleared after the delay.
    val highlightColor = MaterialTheme.colorScheme.secondaryContainer
    val highlightAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(NetworkConfig.motionFadeDurationMs),
        label = "msgHighlight",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (highlightAlpha > 0f) {
                    Modifier.background(highlightColor.copy(alpha = highlightAlpha * 0.6f))
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = assistantRoleLabel }
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val info = message.info
        if (info is AssistantMessage && isFirstOfSpeaker) {
            // Avatar + model label row: the robot icon gives the assistant a consistent visual
            // identity (left-aligned, mirroring the user's right-aligned bubble) so on a long
            // scroll the speaker is unambiguous without reading the label. Suppressed on
            // consecutive same-speaker messages to reduce visual noise in tool-heavy runs.
            AssistantHeader(label = modelLabel ?: info.modelID, agentLabel = agentLabel)
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
        // Agent-reported error: the server sets AssistantMessage.error when the run finished
        // with a model/agent error (distinct from a transport failure, which surfaces as a
        // snackbar). Render it as an error-tinted note above the footer so a reader can tell
        // the reply is incomplete/failed by glancing at the bubble, not just from the absence
        // of content. Folded into the same `info is AssistantMessage` branch as the cost
        // summary below to avoid an extra conditional raising this function's complexity.
        if (info is AssistantMessage) {
            info.error?.let { err -> AgentErrorNote(err) }
            val cost = info.cost
            val tokens = info.tokens
            val tokenFormat = stringResource(R.string.tokens_in_out)
            val reasoningFormat = stringResource(R.string.tokens_reasoning_format)
            val cacheReadFormat = stringResource(R.string.tokens_cache_read_format)
            val cacheWriteFormat = stringResource(R.string.tokens_cache_write_format)
            val costShort = stringResource(R.string.cost_format_short)
            val costLong = stringResource(R.string.cost_format_long)
            // Memoize the formatted cost/tokens line so a scroll-induced or unrelated
            // state-flip recomposition doesn't re-run NumberFormat + buildList +
            // joinToString for every visible assistant bubble. The assembly logic lives in
            // [buildCostSummary] (extracted to keep this function's complexity in check).
            //
            // Reasoning and cache figures are only added when non-zero, so a model that
            // doesn't report them (or a request that didn't use them) shows the same compact
            // "1.2k in · 540 out • $0.012" line as before; a reasoning-model reply gains a
            // "3.1k reasoning" and/or "8.0k cache read" segment. The single-line cap and
            // ellipsis below keep the line tidy at narrow widths even with all four segments.
            val costSummary = remember(info.isComplete, tokens, cost, tokenFormat, reasoningFormat, cacheReadFormat, cacheWriteFormat, costShort, costLong) {
                buildCostSummary(info.isComplete, tokens, cost, tokenFormat, reasoningFormat, cacheReadFormat, cacheWriteFormat, costShort, costLong)
            }
            // Long-press context menu state is hoisted to the block scope (see the Column
            // above) so the whole bubble is the long-press target.
            // Collect text from all TextParts for copy/read-aloud. Memoized so a
            // scroll-induced recomposition doesn't re-scan the parts list.
            val textToCopy = remember(message.parts) {
                message.parts
                    .filterIsInstance<TextPart>()
                    .joinToString("\n\n") { it.text }
                    .takeIf { it.isNotBlank() }
            }
            val onCopy: (() -> Unit)? = textToCopy?.let { text ->
                {
                    assistantHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    copyToClipboard(assistantContext, assistantContext.getString(R.string.clip_label_message), text)
                }
            }
            Box {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    costSummary?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Allow up to 2 lines so a multi-segment summary (in/out • reasoning
                            // • cache read • cost) wraps instead of ellipsizing away the cost —
                            // the part users most want — on narrow screens.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
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
                    ttsState = ttsState,
                    onSpeak = onSpeak,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop,
                    onRevert = onRevert,
                    onQuote = onQuote,
                    onBranch = onBranch,
                    onRegenerate = onRegenerate,
                    onContinue = onContinue,
                    onViewSource = textToCopy?.let { { showSource = true } },
                )
            }
            AssistantMessageLongPressMenu(
                expanded = longPressMenu,
                onDismiss = { longPressMenu = false },
                offset = longPressOffset,
                text = textToCopy,
                onCopy = onCopy,
                onQuote = onQuote,
                onBranch = onBranch,
                onRevert = onRevert,
                onShare = onShare,
                onRegenerate = onRegenerate,
                onSpeak = onSpeak,
                isSpeaking = isSpeaking,
                ttsState = ttsState,
                onPause = onPause,
                onResume = onResume,
                onViewSource = textToCopy?.let { { showSource = true } },
            )
            }
            if (showSource && textToCopy != null) {
                ViewSourceDialog(
                    source = textToCopy,
                    onDismiss = { showSource = false },
                )
            }
        } else {
            MessageTimestampText(message.info)
        }
    }
}

/** Wraps [MessageLongPressMenu] for an assistant reply, building the Read-aloud toggle
 *  callback and label from the current speaking state so the branch count stays out of
 *  [AssistantBlock]. */
@Composable
private fun AssistantMessageLongPressMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    offset: androidx.compose.ui.unit.DpOffset,
    text: String?,
    onCopy: (() -> Unit)?,
    onQuote: ((String) -> Unit)?,
    onBranch: ((String) -> Unit)?,
    onRevert: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onRegenerate: (() -> Unit)?,
    onSpeak: ((String) -> Unit)?,
    isSpeaking: Boolean,
    ttsState: TtsState,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onViewSource: (() -> Unit)? = null,
) {
    val speakForMenu: (() -> Unit)? = if (onSpeak != null && text != null) {
        {
            when {
                isSpeaking && ttsState == TtsState.PAUSED -> onResume?.invoke()
                isSpeaking -> onPause?.invoke()
                else -> onSpeak(text)
            }
        }
    } else {
        null
    }
    val speakLabel: String? = if (onSpeak != null && text != null) {
        stringResource(
            when {
                isSpeaking && ttsState == TtsState.PAUSED -> R.string.resume_reading
                isSpeaking -> R.string.pause_reading
                else -> R.string.read_aloud
            },
        )
    } else {
        null
    }
    MessageLongPressMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        offset = offset,
        text = text,
        onCopy = onCopy,
        onQuote = onQuote,
        onBranch = onBranch,
        onRevert = onRevert,
        onShare = onShare,
        onRegenerate = onRegenerate,
        onSpeak = speakForMenu,
        speakLabel = speakLabel,
        onViewSource = onViewSource,
    )
}

/** Avatar + model label row for an assistant message. Extracted from [AssistantBlock] to keep
 *  that function's cyclomatic complexity under the detekt threshold. When [agentLabel] is
 *  non-null it is shown before the model label, separated by a middle dot
 *  (e.g. "build · claude-sonnet-4-20250514"), matching the top-bar subtitle's format. */
@Composable
private fun AssistantHeader(label: String?, agentLabel: String?) {
    val combined = remember(label, agentLabel) {
        when {
            agentLabel != null && label != null -> "$agentLabel · $label"
            agentLabel != null -> agentLabel
            else -> label
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (combined != null) {
            Text(
                combined,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .weight(1f, fill = false),
            )
        }
    }
}

/** Trailing action buttons for an assistant message: read-aloud (TTS), revert, copy.
 *  Extracted from [AssistantBlock] to keep that function under the complexity threshold. */
@Composable
private fun AssistantActions(
    textToCopy: String?,
    isSpeaking: Boolean,
    ttsState: TtsState,
    onSpeak: ((String) -> Unit)?,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onStop: (() -> Unit)? = null,
    onRevert: (() -> Unit)?,
    onQuote: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onViewSource: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val copyLabel = stringResource(R.string.copy)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Continue: resume a partial assistant reply by sending "continue" without
        // reverting (unlike regenerate, which re-runs from scratch). Shown alongside
        // regenerate so the user can choose between "pick up where it left off" and
        // "start over".
        if (onContinue != null) {
            val continueLabel = stringResource(R.string.continue_run)
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onContinue()
            }) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = continueLabel,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
        // Read-aloud control with pause/resume. Tapping cycles through play → pause → resume,
        // so a user listening to a long reply can step away and continue from roughly where
        // they stopped without re-playing the whole message. Extracted to [TtsButton] to keep
        // this function's cyclomatic complexity under the detekt threshold.
        if (onSpeak != null && textToCopy != null) {
            TtsButton(
                text = textToCopy,
                isSpeaking = isSpeaking,
                ttsState = ttsState,
                onSpeak = onSpeak,
                onPause = onPause,
                onResume = onResume,
                onStop = onStop,
            )
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
        // View source is also available here for assistant replies (in addition to the long-press
        // menu) so the action is discoverable without a long-press. MessageOverflow itself
        // no-ops when all callbacks are null, so the guard only needs to check text.
        if (textToCopy != null) {
            MessageOverflow(text = textToCopy, onQuote = onQuote, onBranch = onBranch, onViewSource = onViewSource)
        }
    }
}

/** Read-aloud button with pause/resume/stop states. Extracted from [AssistantActions] to keep
 *  that function's cyclomatic complexity under the detekt threshold. The icon reflects the
 *  next action: VolumeUp (play) when idle, Pause when playing, PlayArrow (resume) when paused.
 *  A separate Stop button appears when playing or paused so the user can fully stop playback
 *  without tapping a different message. */
@Composable
private fun TtsButton(
    text: String,
    isSpeaking: Boolean,
    ttsState: TtsState,
    onSpeak: (String) -> Unit,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onStop: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val label = when {
        isSpeaking && ttsState == TtsState.PAUSED -> stringResource(R.string.resume_reading)
        isSpeaking -> stringResource(R.string.pause_reading)
        else -> stringResource(R.string.read_aloud)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            when {
                isSpeaking && ttsState == TtsState.PAUSED -> onResume?.invoke()
                isSpeaking -> onPause?.invoke()
                else -> onSpeak(text)
            }
        }) {
            val icon = when {
                isSpeaking && ttsState == TtsState.PAUSED -> Icons.Filled.PlayArrow
                isSpeaking -> Icons.Filled.Pause
                else -> Icons.AutoMirrored.Filled.VolumeUp
            }
            val tint = if (isSpeaking) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
                tint = tint,
            )
        }
        // Stop button: fully stops TTS playback (vs pause which keeps the position). Only
        // shown while playing or paused, so idle messages don't have a dead stop button.
        if (isSpeaking && onStop != null) {
            val stopLabel = stringResource(R.string.stop_reading)
            IconButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onStop()
            }) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = stopLabel,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageTimestampText(info: soy.iko.opencode.data.model.MessageInfo) {
    // Compact relative label by default; long-press reveals the full absolute timestamp
    // (RelativeTimeText renders nothing when the time is absent, so no isNotEmpty guard).
    RelativeTimeText(info.time?.created)
}

/**
 * Render an [AssistantMessage.error] as an error-tinted note on the bubble. The error is a
 * tolerant-decoded [JsonElement]; we extract a readable string from it (a primitive's content
 * for a bare string, a pretty-printed object otherwise, trimmed to a reasonable length so a
 * verbose error object doesn't dominate the bubble).
 */
@Composable
private fun AgentErrorNote(error: kotlinx.serialization.json.JsonElement) {
    // Extract a readable string from the tolerant-decoded JsonElement: a bare string
    // primitive returns its content (the common `{"error": "..."}` case); an object/array
    // is pretty-printed and trimmed so a verbose error doesn't dominate the bubble.
    val fullText = remember(error) {
        val raw = if (error is kotlinx.serialization.json.JsonPrimitive) {
            error.content
        } else {
            runCatching {
                soy.iko.opencode.data.network.OpencodeJson.encodeToString(
                    kotlinx.serialization.json.JsonElement.serializer(),
                    error,
                )
            }.getOrDefault(error.toString())
        }
        raw
    }.takeIf { it.isNotBlank() } ?: return
    val isLong = fullText.length > 500
    var expanded by remember(fullText) { mutableStateOf(false) }
    val text = if (isLong && !expanded) fullText.take(500) + "…" else fullText
    val label = stringResource(R.string.agent_error_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            // SelectionContainer so the user can copy the error text to search for it or
            // share it for support — previously the error was static, unselectable text.
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (isLong) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(
                        stringResource(if (expanded) R.string.hide_full_error else R.string.show_full_error),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
