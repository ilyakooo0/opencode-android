package soy.iko.opencode.ui.chat

import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.FilePart
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ReasoningPart
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.ToolCompleted
import soy.iko.opencode.data.model.ToolError
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.ToolRunning
import soy.iko.opencode.data.model.UserMessage
import soy.iko.opencode.data.model.sourcePath
import soy.iko.opencode.data.network.NetworkConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Render a conversation as Markdown for sharing/exporting. Each message becomes a
 * role-prefixed section containing its text parts; reasoning is exported as a blockquote
 * and tool calls are summarized so the transcript shows what the agent *did*, not just
 * what it said. Pure (no Android deps) so it can be unit-tested directly.
 */
fun buildConversationMarkdown(messages: List<MessageWithParts>, title: String?): String {
    val sb = StringBuilder(messages.size * 128)
    if (!title.isNullOrBlank()) {
        sb.append("# ").append(escapeMarkdown(title.trim())).append("\n\n")
    }
    appendMetadataHeader(sb, messages)
    for (message in messages) {
        val heading = when (message.info) {
            is UserMessage -> "## You"
            is AssistantMessage -> "## opencode"
            else -> null
        } ?: continue
        // The assistant's reply text is already Markdown (code fences, bold, headings);
        // escaping it would render the export as backslash noise. Only escape user-authored
        // text, which isn't expected to be Markdown.
        val escapeText = message.info is UserMessage
        val body = message.parts
            .mapNotNull { part ->
                when (part) {
                    is TextPart -> part.text.takeIf { it.isNotBlank() }
                        ?.let { if (escapeText) escapeMarkdown(it) else it }
                    is ReasoningPart -> part.text.takeIf { it.isNotBlank() }
                        ?.let { text -> text.trim().lines().joinToString("\n") { "> _${escapeMarkdown(it)}_" } }
                    is ToolPart -> formatToolCall(part)
                    is FilePart -> {
                        // Image/file attachments aren't portable in a text export; emit a
                        // labeled placeholder so the transcript records that one was present
                        // (useful for debugging) instead of silently dropping it.
                        val name = part.sourcePath ?: part.url ?: part.filename ?: "attachment"
                        if (part.mime?.startsWith("image/") == true) "_[image: ${escapeMarkdown(name)}]_"
                        else "_[file: ${escapeMarkdown(name)}]_"
                    }
                    else -> null
                }
            }
            .joinToString("\n\n")
            .trim()
        if (body.isEmpty()) continue
        sb.append(heading).append("\n\n")
        // Per-message timestamp (ISO, local zone) so an exported transcript is useful for
        // support/debugging — without it only the metadata header's global range has timing.
        message.info.time?.created?.let { ts -> sb.append("_").append(formatExportTimestamp(ts)).append("_\n\n") }
        sb.append(body).append("\n\n")
    }
    return sb.toString().trimEnd()
}

/**
 * Render a single message as Markdown for per-message sharing. A trimmed-down version of
 * [buildConversationMarkdown] that emits just this message's role heading + body (no metadata
 * header), so a shared single reply/exchange is self-contained without dragging the whole
 * transcript. Returns null when the message has no exportable body (e.g. an image-only prompt).
 */
fun buildMessageMarkdown(message: MessageWithParts): String? {
    val heading = when (message.info) {
        is UserMessage -> "## You"
        is AssistantMessage -> "## opencode"
        else -> return null
    }
    val escapeText = message.info is UserMessage
    val body = message.parts
        .mapNotNull { part ->
            when (part) {
                is TextPart -> part.text.takeIf { it.isNotBlank() }
                    ?.let { if (escapeText) escapeMarkdown(it) else it }
                is ReasoningPart -> part.text.takeIf { it.isNotBlank() }
                    ?.let { text -> text.trim().lines().joinToString("\n") { "> _${escapeMarkdown(it)}_" } }
                is ToolPart -> formatToolCall(part)
                is FilePart -> {
                    val name = part.sourcePath ?: part.url ?: part.filename ?: "attachment"
                    if (part.mime?.startsWith("image/") == true) "_[image: ${escapeMarkdown(name)}]_"
                    else "_[file: ${escapeMarkdown(name)}]_"
                }
                else -> null
            }
        }
        .joinToString("\n\n")
        .trim()
    if (body.isEmpty()) return null
    return buildString {
        append(heading).append("\n\n")
        message.info.time?.created?.let { ts -> append("_").append(formatExportTimestamp(ts)).append("_\n\n") }
        append(body)
    }
}

/** Format an epoch-millis as a locale-stable ISO-ish timestamp for the export. Thread-confined
 *  to the exporting coroutine, so a shared SimpleDateFormat (not thread-safe) is fine. */
private fun formatExportTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

/** Append a metadata header (message counts + timestamp range) so a shared transcript has
 *  context for support/debugging. Skipped when there are no messages. */
private fun appendMetadataHeader(sb: StringBuilder, messages: List<MessageWithParts>) {
    if (messages.isEmpty()) return
    val userCount = messages.count { it.info is UserMessage }
    val assistantCount = messages.count { it.info is AssistantMessage }
    val firstTime = messages.firstOrNull()?.info?.time?.created
    val lastTime = messages.lastOrNull()?.info?.time?.created
    sb.append("> ")
    // Pluralize each noun independently (a prior bug keyed both on userCount, so "user" was
    // never pluralized and "message" was pluralized based on the wrong count).
    sb.append("$userCount user")
    if (userCount != 1) sb.append("s")
    sb.append(" · $assistantCount assistant message")
    if (assistantCount != 1) sb.append("s")
    if (firstTime != null && lastTime != null) {
        sb.append(" · $firstTime → $lastTime")
    }
    sb.append("\n\n---\n\n")
}

/** Render a tool call as a compact, readable blockquote summary: the tool name, its
 *  human-readable title (if any), and a truncated output/error. Keeps the exported
 *  transcript useful when the agent's work (edits, command runs) is the substance. */
private fun formatToolCall(part: ToolPart): String? {
    val title = when (val s = part.state) {
        is ToolRunning -> s.title
        is ToolCompleted -> s.title
        else -> null
    }
    val detail = when (val s = part.state) {
        is ToolCompleted -> s.output?.takeIf { it.isNotBlank() }?.let { truncateOutput(it) }
        is ToolError -> s.error?.takeIf { it.isNotBlank() }?.let { "Error: ${truncateOutput(it)}" }
        else -> null
    }
    val status = when (part.state) {
        is ToolCompleted -> null
        is ToolError -> " — error"
        is ToolRunning -> " — running"
        else -> null
    }
    val head = "**${part.tool}**${status ?: ""}"
    val titleLine = title?.takeIf { it.isNotBlank() }?.let { escapeMarkdown(it) }
    return buildList {
        add("> $head")
        if (titleLine != null) add("> $titleLine")
        // Prefix every detail line with "> " so the fenced code block stays inside the
        // blockquote — otherwise lines after the first fall out of the quote.
        if (detail != null) {
            val quoted = detail.lines().joinToString("\n") { "> $it" }
            // Use a fence longer than the longest backtick run in the content, so tool output
            // that itself contains a ``` sequence (shell output echoing markdown, etc.) can't
            // prematurely close the block and make the rest of the transcript render broken.
            val fence = "`".repeat(maxOf(3, longestBacktickRun(detail) + 1))
            add(">\n> $fence\n$quoted\n> $fence")
        }
    }.joinToString("\n").takeIf { it.isNotBlank() }
}

/** Length of the longest consecutive run of backticks in [text] (0 if none). */
private fun longestBacktickRun(text: String): Int {
    var max = 0
    var current = 0
    for (c in text) {
        if (c == '`') {
            current++
            if (current > max) max = current
        } else {
            current = 0
        }
    }
    return max
}

private fun truncateOutput(output: String): String {
    if (output.length <= NetworkConfig.exportToolOutputLimitChars) return output
    return output.take(NetworkConfig.exportToolOutputLimitChars) + "\n… (truncated)"
}

/** Escape markdown special characters so user/model text doesn't produce malformed markdown.
 *  Extends the basic set with [ ] ( ) - + ! so user text containing links, list markers, or
 *  image syntax doesn't render as live Markdown in the exported transcript. */
private fun escapeMarkdown(text: String): String {
    if (text.isEmpty()) return text
    val sb = StringBuilder(text.length + 16)
    for (c in text) {
        when (c) {
            '\\', '*', '_', '#', '`', '>', '[', ']', '(', ')', '!', '+', '-' -> sb.append('\\')
        }
        sb.append(c)
    }
    return sb.toString()
}
