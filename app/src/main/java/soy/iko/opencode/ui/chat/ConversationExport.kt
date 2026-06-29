package soy.iko.opencode.ui.chat

import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ReasoningPart
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.UserMessage

/**
 * Render a conversation as Markdown for sharing/exporting. Each message becomes a
 * role-prefixed section containing its text parts; reasoning and tool parts are
 * omitted to keep the exported transcript readable. Pure (no Android deps) so it can
 * be unit-tested directly.
 */
fun buildConversationMarkdown(messages: List<MessageWithParts>, title: String?): String {
    val sb = StringBuilder()
    if (!title.isNullOrBlank()) {
        sb.append("# ").append(title.trim()).append("\n\n")
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
                    is TextPart -> part.text.takeIf { it.isNotBlank() }
                    is ReasoningPart -> part.text.takeIf { it.isNotBlank() }?.let { "_${it}_" }
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
