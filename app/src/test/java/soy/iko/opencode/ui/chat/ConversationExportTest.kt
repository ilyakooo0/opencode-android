package soy.iko.opencode.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ReasoningPart
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.ToolCompleted
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.UserMessage

class ConversationExportTest {

    private val sid = "s1"

    private fun user(id: String, text: String) =
        MessageWithParts(UserMessage(id, sid), listOf(TextPart(id = "p$id", text = text)))

    private fun assistant(id: String, text: String) =
        MessageWithParts(AssistantMessage(id, sid), listOf(TextPart(id = "p$id", text = text)))

    @Test
    fun rendersRoleHeadersAndBodies() {
        val md = buildConversationMarkdown(
            listOf(user("u1", "Hello"), assistant("a1", "Hi there!")),
            title = "My chat",
        )
        assertTrue(md.startsWith("# My chat"))
        assertTrue(md.contains("## You\n\nHello"))
        assertTrue(md.contains("## opencode\n\nHi there!"))
    }

    @Test
    fun omitsTitleWhenBlank() {
        val md = buildConversationMarkdown(listOf(user("u1", "hi")), title = null)
        assertTrue(md.startsWith("## You"))
    }

    @Test
    fun skipsMessagesWithoutText() {
        val onlyReasoning = MessageWithParts(
            AssistantMessage("a1", sid),
            listOf(ReasoningPart(id = "r1", text = "hmm")),
        )
        val md = buildConversationMarkdown(listOf(onlyReasoning), title = null)
        // Reasoning is exported in italics, so it still appears.
        assertTrue(md.contains("## opencode"))
        assertTrue(md.contains("_hmm_"))
    }

    @Test
    fun emptyConversationProducesJustTheTitle() {
        // No message bodies to export, but the title header is still emitted.
        assertEquals("# x", buildConversationMarkdown(emptyList(), title = "x"))
    }

    @Test
    fun includesToolCallSummary() {
        val assistant = MessageWithParts(
            AssistantMessage("a1", sid),
            listOf(
                TextPart(id = "t1", text = "Done"),
                ToolPart(id = "tool1", tool = "read", state = ToolCompleted(output = "file contents")),
            ),
        )
        val md = buildConversationMarkdown(listOf(user("u1", "read the file"), assistant), title = null)
        assertTrue(md.contains("## opencode"))
        assertTrue("tool name should appear", md.contains("**read**"))
        assertTrue("tool output should appear", md.contains("file contents"))
    }

    @Test
    fun includesToolCallWithTitle() {
        val assistant = MessageWithParts(
            AssistantMessage("a1", sid),
            listOf(
                ToolPart(
                    id = "tool1",
                    tool = "edit",
                    state = ToolCompleted(title = "Editing src/main.kt", output = "ok"),
                ),
            ),
        )
        val md = buildConversationMarkdown(listOf(assistant), title = null)
        assertTrue(md.contains("**edit**"))
        assertTrue(md.contains("Editing src/main.kt"))
    }
}
