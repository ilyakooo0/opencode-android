package soy.iko.opencode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import soy.iko.opencode.core.MessageStatus
import soy.iko.opencode.core.MessageView
import soy.iko.opencode.core.ToolView
import soy.iko.opencode.ui.theme.MonoStyle

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: MessageView, modifier: Modifier = Modifier) {
    val isUser = message.isUser
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp,
    )

    val context = LocalContext.current

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(bubbleColor)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (message.text.isNotEmpty()) {
                            copyToClipboard(context, message.text)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            message.reasoning?.let { ReasoningBlock(it) }

            message.tools.forEach { ToolRow(it) }

            if (message.text.isNotEmpty()) {
                Text(
                    text = message.text + if (message.streaming) " ▌" else "",
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (message.streaming && message.tools.isEmpty() && message.reasoning == null) {
                Text("▌", color = textColor, style = MaterialTheme.typography.bodyLarge)
            }

            if (message.time > 0) {
                Text(
                    text = timeFormat.format(Date(message.time)),
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (isUser && message.status != MessageStatus.Sent) {
                StatusLine(message.status)
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
}

@Composable
private fun ReasoningBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Text(
                text = if (expanded) "Show less" else "Reasoning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp).widthIn(max = 18.dp),
            )
        }
        if (expanded) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ToolRow(tool: ToolView) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Icon(
            Icons.Filled.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp).widthIn(max = 16.dp),
        )
        Text(
            text = buildString {
                append(tool.name)
                append(" · ")
                append(tool.status)
                tool.title?.let { append(" — "); append(it) }
            },
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
    }
}

@Composable
private fun StatusLine(status: MessageStatus) {
    val (icon, label, tint) = when (status) {
        MessageStatus.Pending -> Triple(Icons.Filled.Schedule, "Sending…", MaterialTheme.colorScheme.onPrimaryContainer)
        MessageStatus.Failed -> Triple(Icons.Filled.ErrorOutline, "Failed to send", MaterialTheme.colorScheme.error)
        MessageStatus.Sent -> Triple(Icons.Filled.Schedule, "", Color.Unspecified)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.widthIn(max = 14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
