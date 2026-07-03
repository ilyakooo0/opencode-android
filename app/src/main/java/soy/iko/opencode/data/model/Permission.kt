package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * A permission request from `permission.updated`. The tool detail lives in [type] +
 * [pattern] + [metadata]; [pattern] is a string or array of strings on the wire, so
 * it's kept as a [JsonElement] and flattened for display.
 */
@Immutable
@Serializable
data class Permission(
    val id: String = "",
    val sessionID: String = "",
    val type: String? = null,
    val pattern: JsonElement? = null,
    val messageID: String? = null,
    val callID: String? = null,
    val title: String? = null,
    // JsonElement (not JsonObject) so a non-object `metadata` shape decodes instead of throwing,
    // matching [pattern]. Not read by the UI today, but kept tolerant for forward-compat.
    val metadata: JsonElement? = null,
    val time: TimeInfo? = null,
) {
    val patternText: String?
        get() = when (val p = pattern) {
            null -> null
            is JsonPrimitive -> p.content
            // Only a JSON array is flattened; any other shape (e.g. an object) would make
            // the previous unguarded `p.jsonArray` throw during composition, so treat it
            // as "no displayable pattern" instead.
            is JsonArray -> p.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", ").ifEmpty { null }
            else -> null
        }
}

/** Allowed values for the permission response body.
 *
 * [SESSION] is a client-side scope: it auto-responds [ONCE] to subsequent permission requests
 * whose (type, pattern) match a remembered set, without showing the dialog. The set lives in
 * [soy.iko.opencode.ui.chat.ChatViewModel] and is cleared on connection change. It is NOT sent
 * to the server (the server only knows `once`/`always`/`reject`); the VM maps it to [ONCE] on
 * the wire and tracks the grant locally. This gives users a middle ground between "allow once"
 * (re-prompts on every file read) and "always" (persists in the server config outliving the app). */
enum class PermissionResponse(val wire: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
    SESSION("once"),
}

/** Body for `POST /session/:id/permissions/:permissionID`. */
@Serializable
data class PermissionReplyBody(val response: String)
