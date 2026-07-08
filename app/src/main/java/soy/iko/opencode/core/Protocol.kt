package soy.iko.opencode.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Wire types for the opencode HTTP + SSE API, and lenient parsers for them.
 *
 * This is the Kotlin counterpart of the Rust core's `protocol.rs`. It is
 * deliberately forgiving: unknown JSON fields are ignored and every union has a
 * catch-all so new server versions never break the stream parser.
 */
object Protocol {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ─── Sessions ────────────────────────────────────────────────────────────

    @Serializable
    data class WireSession(val id: String, val title: String = "")

    fun parseSessions(body: String): List<WireSession> =
        runCatching { json.decodeFromString<List<WireSession>>(body) }.getOrDefault(emptyList())

    fun parseSession(body: String): WireSession? =
        runCatching { json.decodeFromString<WireSession>(body) }.getOrNull()

    // ─── Messages ────────────────────────────────────────────────────────────

    @Serializable
    data class WireTime(val created: Long = 0, val completed: Long? = null)

    @Serializable
    data class WireError(val name: String = "", val data: JsonElement? = null) {
        fun message(): String {
            val msg = ((data as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            return when {
                !msg.isNullOrEmpty() -> msg
                name.isNotEmpty() -> name
                else -> "Unknown error"
            }
        }
    }

    @Serializable
    data class WireMessageInfo(
        val id: String,
        val role: String = "",
        @SerialName("sessionID") val sessionId: String = "",
        val time: WireTime = WireTime(),
        val error: WireError? = null,
    )

    @Serializable
    data class WireMessageEnvelope(
        val info: WireMessageInfo,
        val parts: List<JsonElement> = emptyList(),
    )

    fun parseMessages(body: String): List<WireMessageEnvelope> =
        runCatching { json.decodeFromString<List<WireMessageEnvelope>>(body) }.getOrDefault(emptyList())

    // ─── Parts (content union, parsed by hand for robustness) ─────────────────

    sealed interface Part {
        val id: String
        val messageId: String

        data class Text(override val id: String, override val messageId: String, val text: String, val synthetic: Boolean) : Part
        data class Reasoning(override val id: String, override val messageId: String, val text: String) : Part
        data class Tool(override val id: String, override val messageId: String, val tool: String, val status: String, val title: String?) : Part
    }

    /** Parse a single part JSON object; returns null for kinds we don't render. */
    fun parsePart(obj: JsonObject): Part? {
        val id = obj.str("id")
        val messageId = obj.str("messageID")
        return when (obj.str("type")) {
            "text" -> Part.Text(id, messageId, obj.str("text"), obj.bool("synthetic"))
            "reasoning" -> Part.Reasoning(id, messageId, obj.str("text"))
            "tool" -> {
                val state = obj["state"] as? JsonObject
                val status = state?.str("status")?.ifEmpty { "pending" } ?: "pending"
                val title = state?.str("title")?.ifEmpty { null } ?: state?.str("error")?.ifEmpty { null }
                Part.Tool(id, messageId, obj.str("tool"), status, title)
            }
            else -> null
        }
    }

    // ─── SSE `/event` stream ──────────────────────────────────────────────────

    sealed interface Event {
        data class MessageUpdated(val info: WireMessageInfo) : Event
        data class PartUpdated(val part: Part, val delta: String?) : Event
        data class PartRemoved(val messageId: String, val partId: String) : Event
        data class MessageRemoved(val messageId: String) : Event
        data class SessionIdle(val sessionId: String) : Event
        data class SessionError(val sessionId: String?, val error: WireError?) : Event
        data class SessionUpserted(val info: WireSession) : Event
        data class SessionDeleted(val sessionId: String?) : Event
        data object Other : Event
    }

    /** Parse one raw SSE `data:` payload into an [Event]; null if malformed. */
    fun parseEvent(raw: String): Event? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val props = root["properties"] as? JsonObject ?: JsonObject(emptyMap())
        return when (root.str("type")) {
            "message.updated" -> decode<WireMessageInfo>(props["info"])?.let { Event.MessageUpdated(it) }
            "message.part.updated" -> {
                val partObj = props["part"] as? JsonObject ?: return Event.Other
                parsePart(partObj)?.let { Event.PartUpdated(it, props.str("delta").ifEmpty { null }) } ?: Event.Other
            }
            "message.part.removed" -> Event.PartRemoved(props.str("messageID"), props.str("partID"))
            "message.removed" -> Event.MessageRemoved(props.str("messageID"))
            "session.idle" -> Event.SessionIdle(props.str("sessionID"))
            "session.error" -> Event.SessionError(props.str("sessionID").ifEmpty { null }, decode<WireError>(props["error"]))
            "session.created", "session.updated" -> decode<WireSession>(props["info"])?.let { Event.SessionUpserted(it) }
            "session.deleted" -> Event.SessionDeleted(
                props.str("sessionID").ifEmpty { null } ?: (props["info"] as? JsonObject)?.str("id")?.ifEmpty { null },
            )
            else -> Event.Other
        }
    }

    private inline fun <reified T> decode(el: JsonElement?): T? =
        el?.let { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

    // ─── Small JSON helpers ───────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBoolean() } ?: false
}
