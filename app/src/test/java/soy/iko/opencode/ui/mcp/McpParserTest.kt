package soy.iko.opencode.ui.mcp

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpParserTest {

    @Test
    fun parsesLocalAndRemoteServers() {
        val config = buildJsonObject {
            putJsonObject("mcp") {
                putJsonObject("local-one") {
                    put("type", "local")
                    put("enabled", true)
                    putJsonArray("command") { add("node"); add("server.js") }
                }
                putJsonObject("remote-one") {
                    put("type", "remote")
                    put("url", "https://example.com/mcp")
                    put("enabled", false)
                }
            }
        }
        val servers = parseMcpServers(config)
        assertEquals(2, servers.size)
        val local = servers.first { it.name == "local-one" }
        assertEquals("local", local.type)
        assertTrue(local.enabled)
        assertEquals("node server.js", local.target)
        val remote = servers.first { it.name == "remote-one" }
        assertFalse(remote.enabled)
        assertEquals("https://example.com/mcp", remote.target)
    }

    @Test
    fun defaultsEnabledWhenOmitted() {
        val config = buildJsonObject { putJsonObject("mcp") { putJsonObject("x") { put("type", "local") } } }
        assertTrue(parseMcpServers(config).single().enabled)
    }

    @Test
    fun emptyWhenNoMcpKey() {
        assertTrue(parseMcpServers(buildJsonObject { }).isEmpty())
    }
}
