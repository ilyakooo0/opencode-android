package soy.iko.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.Tokens
import soy.iko.opencode.data.model.UserMessage
import soy.iko.opencode.ui.components.ImageLoadContext
import soy.iko.opencode.ui.components.rememberRelativeTime

/** A single message: user prompts right-aligned in a bubble, assistant output full-width. */
@Composable
fun MessageBubble(
    message: MessageWithParts,
    isRunning: Boolean = false,
    modifier: Modifier = Modifier,
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
                .fillMaxWidth(0.85f)
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
            if (info.isComplete && (cost != null || tokens != null)) {
                val parts = buildList {
                    tokens?.let { add(formatTokens(it)) }
                    cost?.takeIf { it > 0 }?.let { add(formatCost(it)) }
                }
                if (parts.isNotEmpty()) {
                    Text(
                        parts.joinToString("  •  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        MessageTimestamp(message.info)
    }
}

@Composable
private fun MessageTimestamp(info: soy.iko.opencode.data.model.MessageInfo) {
    val t = rememberRelativeTime(info.time?.created)
    if (t.isNotEmpty()) {
        Text(
            t,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTokens(tokens: Tokens): String {
    val dec = java.text.DecimalFormat.getNumberInstance(java.util.Locale.US)
    return "${dec.format(tokens.input)} in · ${dec.format(tokens.output)} out"
}

private fun formatCost(cost: Double): String =
    // Locale.US so the formatting is stable regardless of device locale (avoids
    // non-ASCII digits or comma decimal separators in a dollar amount).
    if (cost < 0.01) String.format(java.util.Locale.US, "$%.4f", cost)
    else String.format(java.util.Locale.US, "$%.2f", cost)
