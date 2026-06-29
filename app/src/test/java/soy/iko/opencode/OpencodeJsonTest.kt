package soy.iko.opencode

import soy.iko.opencode.data.model.AssistantMessage
import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.data.model.MessagePartUpdated
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.Part
import soy.iko.opencode.data.model.SessionIdle
import soy.iko.opencode.data.model.TextPart
import soy.iko.opencode.data.model.ToolCompleted
import soy.iko.opencode.data.model.ToolPart
import soy.iko.opencode.data.model.UnknownEvent
import soy.iko.opencode.data.model.UnknownPart
import soy.iko.opencode.data.network.OpencodeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpencodeJsonTest {

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, json: String): T =
        OpencodeJson.decodeFromString(serializer, json)

    @Test
    fun decodesPromptResponseWithPartsAndTokens() {
        val json = """
            {
              "info": {
                "role": "assistant", "id": "msg_1", "sessionID": "ses_1",
                "modelID": "claude-sonnet", "providerID": "anthropic",
                "cost": 0.012,
                "tokens": { "input": 10, "output": 20, "reasoning": 0, "cache": { "read": 1, "write": 2 } },
                "time": { "created": 100, "completed": 200 }
              },
              "parts": [
                { "type": "text", "id": "prt_1", "messageID": "msg_1", "sessionID": "ses_1", "text": "Hello" }
              ]
            }
        """.trimIndent()

        val msg = decode(MessageWithParts.serializer(), json)
        val info = msg.info as AssistantMessage
        assertEquals("anthropic", info.providerID)
        assertEquals(20L, info.tokens?.output)
        assertTrue(info.isComplete)
        assertEquals("Hello", (msg.parts.single() as TextPart).text)
    }

    @Test
    fun decodesToolPartStateMachine() {
        val json = """
            { "type": "tool", "id": "prt_2", "messageID": "msg_1", "callID": "c1", "tool": "bash",
              "state": { "status": "completed", "output": "done", "title": "ran bash" } }
        """.trimIndent()
        val part = decode(Part.serializer(), json) as ToolPart
        assertEquals("bash", part.tool)
        assertEquals("done", (part.state as ToolCompleted).output)
    }

    @Test
    fun decodesPartUpdatedEvent() {
        val json = """
            { "type": "message.part.updated",
              "properties": { "part": { "type": "text", "id": "prt_1", "messageID": "msg_1", "text": "Hel" } } }
        """.trimIndent()
        val event = decode(BusEvent.serializer(), json) as MessagePartUpdated
        assertEquals("Hel", (event.properties.part as TextPart).text)
    }

    @Test
    fun decodesSessionIdleEvent() {
        val event = decode(BusEvent.serializer(), """{"type":"session.idle","properties":{"sessionID":"ses_1"}}""")
        assertTrue(event is SessionIdle)
        assertEquals("ses_1", (event as SessionIdle).properties.sessionID)
    }

    @Test
    fun unknownEventTypeFallsBackInsteadOfThrowing() {
        val event = decode(BusEvent.serializer(), """{"type":"some.future.event","properties":{"x":1}}""")
        assertEquals(UnknownEvent, event)
    }

    @Test
    fun unknownPartTypeFallsBackInsteadOfThrowing() {
        val part = decode(Part.serializer(), """{"type":"future-part","id":"p9","foo":true}""")
        assertTrue(part is UnknownPart)
    }
}
