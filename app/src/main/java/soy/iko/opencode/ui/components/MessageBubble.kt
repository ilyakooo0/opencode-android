package soy.iko.opencode.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import soy.iko.opencode.core.MessageStatus
import soy.iko.opencode.core.MessageView
import soy.iko.opencode.core.ToolView
import soy.iko.opencode.ui.theme.MonoStyle

private val timeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: MessageView, modifier: Modifier = Modifier, onRetry: () -> Unit = {}, compactSpacing: Boolean = false) {
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
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compactSpacing) 1.dp else 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Top).padding(end = 6.dp, top = 2.dp).size(28.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(bubbleColor)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (message.text.isNotEmpty()) {
                            showMenu = true
                        }
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        copyToClipboard(context, message.text)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        showMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = {
                        shareText(context, message.text)
                        showMenu = false
                    },
                )
            }

            message.reasoning?.let { ReasoningBlock(it, context) }

            // Give a quick overview of how many tools ran before listing them.
            if (message.tools.size > 1) {
                Text(
                    text = "${message.tools.size} tool calls",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            message.tools.forEach { ToolRow(it) }

            SelectionContainer {
                if (message.text.isNotEmpty()) {
                    val linkColor = MaterialTheme.colorScheme.primary
                    // Tint the code background off the bubble's own foreground colour so it
                    // contrasts on both bubble types (user bubbles use primaryContainer).
                    val codeBackground = (if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.08f)
                    val annotatedText = remember(message.text, message.streaming, linkColor, codeBackground, context) {
                        buildLinkedText(message.text, message.streaming, linkColor, codeBackground, context)
                    }
                    Text(
                        text = annotatedText,
                        color = textColor,
                        // Give assistant replies (often long, code-heavy) extra line
                        // spacing for readability; user bubbles keep the default.
                        style = if (isUser) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp)
                        },
                    )
                } else if (message.streaming && message.tools.isEmpty() && message.reasoning == null) {
                    Text("▌", color = textColor, style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (message.time > 0) {
                Text(
                    text = timeFormat.format(java.time.Instant.ofEpochMilli(message.time).atZone(java.time.ZoneId.systemDefault()).toLocalTime()),
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (isUser && message.status != MessageStatus.Sent) {
                StatusLine(message.status, onRetry = onRetry)
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
}

private val urlRegex = Regex("""https?://[^\s]+""")

/**
 * Render [text] as an AnnotatedString. Triple-backtick fences split the text into
 * prose and code segments: code segments are rendered monospace on a tinted
 * [codeBackground] (fences dropped), while prose has each http(s) URL turned into a
 * clickable link. The streaming cursor (" ▌") is appended outside any annotation.
 */
private fun buildLinkedText(
    text: String,
    streaming: Boolean,
    linkColor: Color,
    codeBackground: Color,
    context: Context,
): AnnotatedString = buildAnnotatedString {
    // Odd-indexed segments sit between a pair of ``` fences, so they're code. An
    // unclosed fence leaves the trailing segment styled as code, mirroring markdown.
    val segments = text.split("```")
    segments.forEachIndexed { index, segment ->
        if (index % 2 == 1) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(segment)
            }
        } else {
            appendLinkedText(segment, linkColor, context)
        }
    }
    if (streaming) append(" ▌")
}

/** Append [text] to the builder, turning each http(s) URL into a clickable link. */
private fun AnnotatedString.Builder.appendLinkedText(
    text: String,
    linkColor: Color,
    context: Context,
) {
    val matches = urlRegex.findAll(text).toList()
    var cursor = 0
    for (match in matches) {
        append(text.substring(cursor, match.range.first))
        val url = match.value
        withLink(
            LinkAnnotation.Clickable(
                tag = url,
                styles = TextLinkStyles(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                ),
                linkInteractionListener = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
            ),
        ) {
            append(url)
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

@Composable
private fun ReasoningBlock(text: String, context: Context) {
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
            Text(
                text = "Copy",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable {
                        copyToClipboard(context, text)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
            )
        }
    }
}

@Composable
private fun ToolRow(tool: ToolView) {
    var expanded by remember { mutableStateOf(false) }
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val statusColor = when (tool.status) {
        "completed", "success" -> MaterialTheme.colorScheme.primary
        "error", "failed" -> MaterialTheme.colorScheme.error
        "pending", "running", "in_progress" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(bottom = 6.dp),
    ) {
        Icon(
            Icons.Filled.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp).widthIn(max = 16.dp),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = baseColor)) {
                    append(tool.name)
                    append(" · ")
                }
                withStyle(SpanStyle(color = statusColor)) {
                    append(tool.status)
                }
                if (expanded) tool.title?.let {
                    withStyle(SpanStyle(color = baseColor)) {
                        append(" — ")
                        append(it)
                    }
                }
            },
            style = MonoStyle,
            color = baseColor,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse tool details" else "Expand tool details",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp).widthIn(max = 18.dp),
        )
    }
}

@Composable
private fun StatusLine(status: MessageStatus, onRetry: () -> Unit) {
    val (icon, label, tint) = when (status) {
        MessageStatus.Pending -> Triple(Icons.Filled.Schedule, "Sending…", MaterialTheme.colorScheme.onPrimaryContainer)
        MessageStatus.Failed -> Triple(Icons.Filled.ErrorOutline, "Failed to send", MaterialTheme.colorScheme.error)
        MessageStatus.Sent -> Triple(Icons.Filled.Check, "", Color.Unspecified)
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
        if (status == MessageStatus.Failed) {
            Text(
                text = "Retry",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable { onRetry() },
            )
        }
    }
}
