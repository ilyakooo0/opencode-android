package soy.iko.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.Tokens
import soy.iko.opencode.data.model.UserMessage
import soy.iko.opencode.data.network.NetworkConfig
import soy.iko.opencode.ui.components.ImageLoadContext
import soy.iko.opencode.ui.components.copyToClipboard
import soy.iko.opencode.ui.components.rememberRelativeTime

/** A single message: user prompts right-aligned in a bubble, assistant output full-width. */
@Composable
fun MessageBubble(
    message: MessageWithParts,
    modifier: Modifier = Modifier,
    isRunning: Boolean = false,
    imageContext: ImageLoadContext? = null,
) {
    when (message.info) {
        is UserMessage -> UserBubble(message, imageContext, modifier)
        else -> AssistantBlock(message, isRunning, imageContext, modifier)
    }
}

@Composable
private fun UserBubble(message: MessageWithParts, imageContext: ImageLoadContext?, modifier: Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .fillMaxWidth(NetworkConfig.userBubbleWidthFraction)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (part in message.parts) PartView(part, imageContext = imageContext)
            MessageTimestamp(message.info)
        }
    }
}

@Composable
private fun AssistantBlock(message: MessageWithParts, isRunning: Boolean, imageContext: ImageLoadContext?, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val info = message.info
        if (info is AssistantMessage && info.modelID != null) {
            Text(
                info.modelID,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (part in message.parts) {
            PartView(part, isRunning = isRunning, modifier = Modifier.fillMaxWidth(), imageContext = imageContext)
        }
        if (info is AssistantMessage) {
            val cost = info.cost
            val tokens = info.tokens
            val tokenFormat = stringResource(R.string.tokens_in_out)
            // Memoize the formatted cost/tokens line so a scroll-induced or unrelated
            // state-flip recomposition doesn't re-run NumberFormat + buildList +
            // joinToString for every visible assistant bubble.
            val costSummary = remember(info.isComplete, tokens, cost, tokenFormat) {
                if (!info.isComplete || (cost == null && tokens == null)) null
                else buildList {
                    tokens?.let { add(formatTokens(it, tokenFormat)) }
                    cost?.takeIf { it > 0 }?.let { add(formatCost(it)) }
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
                            modifier = Modifier.size(14.dp),
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

@Composable
private fun MessageTimestamp(info: soy.iko.opencode.data.model.MessageInfo) {
    MessageTimestampText(info)
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

private fun formatCost(cost: Double): String =
    // Locale.US so the formatting is stable regardless of device locale (avoids
    // non-ASCII digits or comma decimal separators in a dollar amount).
    if (cost < 0.01) String.format(java.util.Locale.US, "$%.4f", cost)
    else String.format(java.util.Locale.US, "$%.2f", cost)
