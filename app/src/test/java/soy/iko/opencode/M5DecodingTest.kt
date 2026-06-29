package soy.iko.opencode

import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.data.model.FileContent
import soy.iko.opencode.data.model.FileNode
import soy.iko.opencode.data.model.PermissionReplied
import soy.iko.opencode.data.model.PermissionUpdated
import soy.iko.opencode.data.model.ProvidersResponse
import soy.iko.opencode.data.model.defaultOption
import soy.iko.opencode.data.model.toOptions
import soy.iko.opencode.data.network.OpencodeJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M5DecodingTest {

    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, json: String): T =
        OpencodeJson.decodeFromString(s, json)

    @Test
    fun decodesPermissionUpdatedWithArrayPattern() {
        val json = """
            { "type": "permission.updated", "properties": {
                "id": "perm_1", "sessionID": "ses_1", "type": "bash",
                "pattern": ["rm -rf *", "git push"], "title": "Run bash",
                "metadata": { "cwd": "/tmp" }, "time": { "created": 123 } } }
        """.trimIndent()
        val event = decode(BusEvent.serializer(), json) as PermissionUpdated
        assertEquals("perm_1", event.properties.id)
        assertEquals("bash", event.properties.type)
        assertEquals("rm -rf *, git push", event.properties.patternText)
    }

    @Test
    fun decodesPermissionUpdatedWithStringPattern() {
        val json = """
            { "type": "permission.updated", "properties": {
                "id": "perm_2", "sessionID": "ses_1", "type": "edit", "pattern": "src/Main.kt" } }
        """.trimIndent()
        val event = decode(BusEvent.serializer(), json) as PermissionUpdated
        assertEquals("src/Main.kt", event.properties.patternText)
    }

    @Test
    fun decodesPermissionReplied() {
        val json = """{"type":"permission.replied","properties":{"sessionID":"s","permissionID":"perm_1","response":"once"}}"""
        val event = decode(BusEvent.serializer(), json) as PermissionReplied
        assertEquals("perm_1", event.properties.permissionID)
        assertEquals("once", event.properties.response)
    }

    @Test
    fun decodesRichProvidersAndFlattens() {
        // Mirrors the real (richer) /config/providers shape; unknown fields must be ignored.
        val json = """
            {
              "providers": [
                { "id": "anthropic", "name": "Anthropic", "source": "env", "env": ["ANTHROPIC_API_KEY"],
                  "options": {},
                  "models": {
                    "claude-sonnet": { "id": "claude-sonnet", "providerID": "anthropic", "name": "Claude Sonnet",
                      "capabilities": { "reasoning": true }, "cost": { "input": 3, "output": 15 },
                      "limit": { "context": 200000, "output": 8192 }, "status": "active" }
                  } }
              ],
              "default": { "anthropic": "claude-sonnet" }
            }
        """.trimIndent()
        val resp = decode(ProvidersResponse.serializer(), json)
        val options = resp.toOptions()
        assertEquals(1, options.size)
        assertEquals("Claude Sonnet", options.single().modelLabel)
        assertEquals("claude-sonnet", resp.defaultOption(options)?.modelID)
    }

    @Test
    fun decodesFileContentAndDirectoryListing() {
        val content = decode(FileContent.serializer(), """{"type":"text","content":"hello\nworld"}""")
        assertEquals("hello\nworld", content.content)
        assertTrue(!content.isBinary)

        val nodes = decode(
            ListSerializer(FileNode.serializer()),
            """[{"name":"src","path":"src","type":"directory"},{"name":"a.kt","path":"src/a.kt","type":"file"}]""",
        )
        assertEquals(2, nodes.size)
        assertTrue(nodes[0].isDirectory)
        assertTrue(!nodes[1].isDirectory)
    }

    @Test
    fun decodesFindFilesAsStringArray() {
        val paths = decode(ListSerializer(String.serializer()), """["src/a.kt","src/b.kt"]""")
        assertEquals(listOf("src/a.kt", "src/b.kt"), paths)
    }

    @Test
    fun binaryFileContentIsFlagged() {
        val content = decode(FileContent.serializer(), """{"type":"binary","content":"AAAA","encoding":"base64","mimeType":"image/png"}""")
        assertTrue(content.isBinary)
        assertNull(content.diff)
    }
}
