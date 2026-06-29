package soy.iko.opencode.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

// --- Block model ------------------------------------------------------------

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    data class ListBlock(val ordered: Boolean, val items: List<ListItem>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Hr : MdBlock
}

internal data class ListItem(val text: String, val depth: Int)

// --- Block parser -----------------------------------------------------------

private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val hrRegex = Regex("^([-*_])\\1{2,}$")
private val listRegex = Regex("^(\\s*)([-*+]|\\d+\\.)\\s+(.+)$")

internal fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) { i++; continue }

        // Fenced code block
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                sb.append(lines[i]).append('\n')
                i++
            }
            i++
            blocks.add(MdBlock.Code(lang, sb.toString().trimEnd('\n')))
            continue
        }

        // Horizontal rule
        if (trimmed.matches(hrRegex)) { blocks.add(MdBlock.Hr); i++; continue }

        // Heading
        headingRegex.matchEntire(trimmed)?.let { m ->
            blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
            i++
            return@let
        } ?: run {
            // Blockquote
            if (trimmed.startsWith(">")) {
                val sb = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    sb.appendLine(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Quote(sb.toString().trim()))
                return@run
            }

            // List
            if (listRegex.matchEntire(trimmed) != null) {
                val items = mutableListOf<ListItem>()
                val ordered = trimmed.matches(Regex("^\\s*\\d+\\.\\s+.+"))
                while (i < lines.size) {
                    val l = lines[i]
                    val lm = listRegex.matchEntire(l)
                    if (lm != null) {
                        val depth = (lm.groupValues[1].length / 2).coerceAtMost(4)
                        items.add(ListItem(lm.groupValues[3].trim(), depth))
                        i++
                    } else if (l.isBlank()) {
                        i++
                    } else if (l.startsWith(" ") || l.startsWith("\t")) {
                        if (items.isNotEmpty()) {
                            items[items.lastIndex] = items.last().copy(text = items.last().text + " " + l.trim())
                        }
                        i++
                    } else {
                        break
                    }
                }
                blocks.add(MdBlock.ListBlock(ordered, items))
                return@run
            }

            // Paragraph
            val sb = StringBuilder(trimmed)
            i++
            while (i < lines.size && lines[i].isNotBlank() &&
                !lines[i].trim().startsWith("```") &&
                !lines[i].trim().startsWith("#") &&
                !lines[i].trim().startsWith(">") &&
                !lines[i].trim().matches(hrRegex) &&
                listRegex.matchEntire(lines[i].trim()) == null
            ) {
                sb.append('\n').append(lines[i].trim())
                i++
            }
            blocks.add(MdBlock.Paragraph(sb.toString()))
        }
    }
    return blocks
}

// --- Inline parser ----------------------------------------------------------

private data class InlineColors(
    val link: androidx.compose.ui.graphics.Color,
    val codeBg: androidx.compose.ui.graphics.Color,
)

private fun renderInline(text: String, colors: InlineColors): AnnotatedString = buildAnnotatedString {
    var i = 0

    fun pushText(s: String) { append(s) }

    fun findClosing(start: Int, delim: String): Int {
        val idx = text.indexOf(delim, start)
        return if (idx >= 0) idx else -1
    }

    while (i < text.length) {
        // Inline code
        if (text.startsWith("`", i)) {
            val end = findClosing(i + 1, "`")
            if (end > i) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBg))
                pushText(text.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }
        // Bold
        if (text.startsWith("**", i)) {
            val end = findClosing(i + 2, "**")
            if (end > i) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                pushText(text.substring(i + 2, end))
                pop()
                i = end + 2
                continue
            }
        }
        // Strikethrough
        if (text.startsWith("~~", i)) {
            val end = findClosing(i + 2, "~~")
            if (end > i) {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                pushText(text.substring(i + 2, end))
                pop()
                i = end + 2
                continue
            }
        }
        // Italic (*text*)
        if (text[i] == '*' && (i + 1 >= text.length || text[i + 1] != '*')) {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                pushText(text.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }
        // Link [text](url)
        if (text[i] == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket) {
                    val label = text.substring(i + 1, closeBracket)
                    pushStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
                    pushText(label)
                    pop()
                    i = closeParen + 1
                    continue
                }
            }
        }
        pushText(text[i].toString())
        i++
    }
}

// --- Composable -------------------------------------------------------------

/**
 * Renders a markdown string with headings, code blocks, lists, blockquotes,
 * inline bold/italic/code/links, and horizontal rules.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val colors = InlineColors(
        link = MaterialTheme.colorScheme.primary,
        codeBg = MaterialTheme.colorScheme.surfaceVariant,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    text = renderInline(block.text, colors),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp, bottom = 2.dp),
                )

                is MdBlock.Code -> CodeBlock(block.code)

                is MdBlock.ListBlock -> {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        block.items.forEachIndexed { index, item ->
                            Row(modifier = Modifier.padding(start = (item.depth * 12).dp, bottom = 2.dp)) {
                                Text(
                                    if (block.ordered) "${index + 1}. " else "•  ",
                                    style = style,
                                )
                                Text(
                                    renderInline(item.text, colors),
                                    style = style,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                is MdBlock.Quote -> {
                    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        text = renderInline(block.text, colors),
                        style = style.copy(fontStyle = FontStyle.Italic),
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawBehind {
                                drawLine(
                                    quoteColor.copy(alpha = 0.4f),
                                    Offset(0f, 0f),
                                    Offset(0f, size.height),
                                    strokeWidth = 3.dp.toPx(),
                                )
                            }
                            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                        color = quoteColor,
                    )
                }

                is MdBlock.Paragraph -> Text(
                    text = renderInline(block.text, colors),
                    style = style,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                MdBlock.Hr -> HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .drawBehind { drawRect(bg) }
            .padding(10.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
