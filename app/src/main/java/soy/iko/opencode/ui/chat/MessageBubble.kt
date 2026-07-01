package soy.iko.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import soy.iko.opencode.R
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelOption
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.Tokens
import soy.iko.opencode.data.model.UnknownMessage
import soy.iko.opencode.data.model.UserMessage
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.ui.components.ImageLoadContext
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.rememberRelativeTime

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

/** A single message: user prompts right-aligned in a bubble, assistant output full-width. */
@Composable
fun MessageBubble(
    message: MessageWithParts,
    modifier: Modifier = Modifier,
    isRunning: Boolean = false,
    imageContext: ImageLoadContext? = null,
    modelLabel: String? = null,
    onOpenFile: ((String) -> Unit)? = null,
) {
    when (message.info) {
        is UserMessage -> UserBubble(message, imageContext, modifier, onOpenFile)
        is UnknownMessage -> UnknownMessageBlock(message, imageContext, modifier, onOpenFile)
        else -> AssistantBlock(message, isRunning, imageContext, modifier, modelLabel, onOpenFile)
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
        for (part in message.parts) {
            PartView(part, imageContext = imageContext, onOpenFile = onOpenFile)
        }
        MessageTimestampText(message.info)
    }
}

@Composable
private fun UserBubble(
    message: MessageWithParts,
    imageContext: ImageLoadContext?,
    modifier: Modifier,
    onOpenFile: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.copy)
    // Collect text from all TextParts for copying, so a user can reuse/repost their
    // own prompt. Memoized so a scroll-induced recomposition doesn't re-scan the list.
    val textToCopy = remember(message.parts) {
        message.parts
            .filterIsInstance<TextPart>()
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotBlank() }
    }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .fillMaxWidth(NetworkConfig.userBubbleWidthFraction)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (part in message.parts) PartView(part, imageContext = imageContext, onOpenFile = onOpenFile)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MessageTimestampText(message.info)
                // Copy affordance for user prompts, mirroring the assistant block.
                // Without it, reusing a prior prompt requires discovering the
                // long-press on the markdown text (and only TextParts support that).
                if (textToCopy != null) {
                    IconButton(
                        onClick = { copyToClipboard(context, "message", textToCopy) },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = copyLabel,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBlock(
    message: MessageWithParts,
    isRunning: Boolean,
    imageContext: ImageLoadContext?,
    modifier: Modifier,
    modelLabel: String? = null,
    onOpenFile: ((String) -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val info = message.info
        if (info is AssistantMessage) {
            val label = modelLabel ?: info.modelID
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message.parts.forEachIndexed { index, part ->
            // Only the final part of a running message is actively streaming; earlier parts
            // (a finished reasoning block, a completed tool call) are already done, so passing
            // isRunning to all of them would keep a completed reasoning block showing the
            // "Thinking…" spinner until the whole message finishes.
            val partStreaming = isRunning && index == message.parts.lastIndex
            PartView(part, isRunning = partStreaming, modifier = Modifier.fillMaxWidth(), imageContext = imageContext, onOpenFile = onOpenFile)
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
                        )
                    }
                    MessageTimestampText(message.info)
                }
                val context = LocalContext.current
                val copyLabel = stringResource(R.string.copy)
                // Collect text from all TextParts for copying. Memoized so a
                // scroll-induced recomposition doesn't re-scan the parts list.
                val textToCopy = remember(message.parts) {
                    message.parts
                        .filterIsInstance<TextPart>()
                        .joinToString("\n\n") { it.text }
                        .takeIf { it.isNotBlank() }
                }
                if (textToCopy != null) {
                    IconButton(
                        onClick = { copyToClipboard(context, "message", textToCopy) },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = copyLabel,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            MessageTimestampText(message.info)
        }
    }
}

@Composable
private fun MessageTimestampText(info: soy.iko.opencode.data.model.MessageInfo) {
    val t = rememberRelativeTime(info.time?.created)
    if (t.isNotEmpty()) {
        Text(
            t,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
