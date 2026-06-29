package soy.iko.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.iko.opencode.data.model.Agent
import soy.iko.opencode.data.model.Command
import soy.iko.opencode.data.network.OpencodeJson
import soy.iko.opencode.ui.components.DiffLine
import soy.iko.opencode.ui.components.looksLikeDiff
import soy.iko.opencode.ui.components.parseDiff
import kotlinx.serialization.builtins.ListSerializer

class M6Test {

    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, json: String): T =
        OpencodeJson.decodeFromString(s, json)

    // --- Agent / Command decoding ---

    @Test
    fun decodesAgentList() {
        val json = """
            [
              { "name": "build", "description": "Build and implement code", "mode": "primary",
                "builtIn": true, "tools": {}, "options": {} },
              { "name": "custom", "mode": "subagent", "builtIn": false }
            ]
        """.trimIndent()
        val agents = decode(ListSerializer(Agent.serializer()), json)
        assertEquals(2, agents.size)
        assertEquals("build", agents[0].name)
        assertTrue(agents[0].isPrimary)
        assertTrue(agents[0].builtIn)
        assertEquals("Build and implement code", agents[0].displayDescription)
        assertFalse(agents[1].isPrimary)
        assertEquals("Custom agent", agents[1].displayDescription)
    }

    @Test
    fun decodesCommandList() {
        val json = """
            [
              { "name": "compact", "description": "Compact the session",
                "template": "Summarize the conversation so far", "subtask": false },
              { "name": "test", "agent": "build", "template": "Run the tests" }
            ]
        """.trimIndent()
        val commands = decode(ListSerializer(Command.serializer()), json)
        assertEquals(2, commands.size)
        assertEquals("compact", commands[0].name)
        assertEquals("Compact the session", commands[0].displayDescription)
        assertEquals("build", commands[1].agent)
    }

    // --- Diff parsing ---

    @Test
    fun parsesUnifiedDiff() {
        val diff = """
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -10,3 +10,4 @@
             fun main() {
            -    println("old")
            +    println("new")
            +    // added
             }
        """.trimIndent()
        val lines = parseDiff(diff)
        assertTrue(lines[0] is DiffLine.FileHeader)
        assertTrue(lines[1] is DiffLine.FileHeader)
        assertTrue(lines[2] is DiffLine.Hunk)
        assertTrue(lines[3] is DiffLine.Context)
        assertTrue(lines[4] is DiffLine.Remove)
        assertTrue(lines[5] is DiffLine.Add)
        assertTrue(lines[6] is DiffLine.Add)
        assertTrue(lines[7] is DiffLine.Context)
        assertEquals("    println(\"old\")", (lines[4] as DiffLine.Remove).text)
        assertEquals("    println(\"new\")", (lines[5] as DiffLine.Add).text)
    }

    @Test
    fun looksLikeDiffDetectsRealDiffs() {
        val realDiff = """
            @@ -1,2 +1,2 @@
            -old line
            +new line
        """.trimIndent()
        assertTrue(looksLikeDiff(realDiff))

        val plainText = "This is just a regular message from the assistant."
        assertFalse(looksLikeDiff(plainText))
    }

    @Test
    fun looksLikeDiffIgnoresStrayMarkers() {
        assertFalse(looksLikeDiff("not a diff at all"))
        assertFalse(looksLikeDiff("just some + text - here"))
    }
}
