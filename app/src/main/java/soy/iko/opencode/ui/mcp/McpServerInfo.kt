package soy.iko.opencode.ui.mcp

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A configured MCP (Model Context Protocol) server, as read from the server's `config.mcp`
 * map. [type] is "local" (spawns a command) or "remote" (connects to a URL); [target] is the
 * command line or URL, whichever applies. Best-effort — fields the server omits are null.
 */
@Immutable
data class McpServerInfo(
    val name: String,
    val type: String?,
    val enabled: Boolean,
    val target: String?,
)

/**
 * Extract the configured MCP servers from a resolved opencode `config` object. Tolerant of
 * shape drift: a non-object entry, or missing fields, degrade gracefully rather than throwing,
 * so a server-side config addition never breaks the viewer. Pure for testability.
 */
fun parseMcpServers(config: JsonObject): List<McpServerInfo> {
    val mcp = config["mcp"] as? JsonObject ?: return emptyList()
    return mcp.entries.map { (name, value) ->
        val obj = value as? JsonObject
        if (obj == null) {
            McpServerInfo(name = name, type = null, enabled = true, target = null)
        } else {
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull
            val command = (obj["command"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.joinToString(" ")
                ?.takeIf { it.isNotBlank() }
            McpServerInfo(name = name, type = type, enabled = enabled, target = url ?: command)
        }
    }.sortedBy { it.name.lowercase() }
}
