package soy.iko.opencode.ui.chat

import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ReasoningPart
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.ToolCompleted
import soy.iko.opencode.data.model.ToolError
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.ToolRunning
import soy.iko.opencode.data.model.UserMessage

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
    for (message in messages) {
        val heading = when (message.info) {
            is UserMessage -> "## You"
            is AssistantMessage -> "## opencode"
            else -> null
        } ?: continue
        val body = message.parts
            .mapNotNull { part ->
                when (part) {
                    is TextPart -> part.text.takeIf { it.isNotBlank() }?.let { escapeMarkdown(it) }
                    is ReasoningPart -> part.text.takeIf { it.isNotBlank() }
                        ?.let { text -> text.trim().lines().joinToString("\n") { "> _${escapeMarkdown(it)}_" } }
                    is ToolPart -> formatToolCall(part)
                    else -> null
                }
            }
            .joinToString("\n\n")
            .trim()
        if (body.isEmpty()) continue
        sb.append(heading).append("\n\n").append(body).append("\n\n")
    }
    return sb.toString().trimEnd()
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
            add(">\n> ```\n$quoted\n> ```")
        }
    }.joinToString("\n").takeIf { it.isNotBlank() }
}

private fun truncateOutput(output: String): String {
    if (output.length <= TOOL_OUTPUT_LIMIT) return output
    return output.take(TOOL_OUTPUT_LIMIT) + "\n… (truncated)"
}

private const val TOOL_OUTPUT_LIMIT = 2000

/** Escape markdown special characters so user/model text doesn't produce malformed markdown. */
private fun escapeMarkdown(text: String): String {
    if (text.isEmpty()) return text
    val sb = StringBuilder(text.length + 16)
    for (c in text) {
        when (c) {
            '\\', '*', '_', '#', '`', '>' -> sb.append('\\')
        }
        sb.append(c)
    }
    return sb.toString()
}
