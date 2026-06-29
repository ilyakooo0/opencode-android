package soy.iko.opencode.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * A permission request from `permission.updated`. The tool detail lives in [type] +
 * [pattern] + [metadata]; [pattern] is a string or array of strings on the wire, so
 * it's kept as a [JsonElement] and flattened for display.
 */
@Serializable
data class Permission(
    val id: String,
    val sessionID: String = "",
    val type: String? = null,
    val pattern: JsonElement? = null,
    val messageID: String? = null,
    val callID: String? = null,
    val title: String? = null,
    val metadata: JsonObject? = null,
    val time: TimeInfo? = null,
) {
    val patternText: String?
        get() = when (val p = pattern) {
            null -> null
            is JsonPrimitive -> p.content
            else -> runCatching { p.jsonArray.joinToString(", ") { (it as JsonPrimitive).content } }.getOrNull()
        }
}

/** Allowed values for the permission response body. */
enum class PermissionResponse(val wire: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}

/** Body for `POST /session/:id/permissions/:permissionID`. */
@Serializable
data class PermissionReplyBody(val response: String)
