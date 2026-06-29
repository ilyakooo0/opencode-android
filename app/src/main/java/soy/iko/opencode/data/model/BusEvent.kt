package soy.iko.opencode.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The SSE envelope from `GET /event`: `{ "type": "<event>", "properties": { ... } }`.
 * Discriminated on `type` (the global discriminator). Unknown events decode to
 * [UnknownEvent] so the stream never dies on a new event type.
 *
 * Property shapes are kept lenient (nullable fields + ignoreUnknownKeys) because they
 * drift across opencode versions; the reducer only reads the fields it needs.
 */
@Serializable
sealed interface BusEvent

@Serializable
@SerialName("server.connected")
data object ServerConnected : BusEvent

@Serializable
@SerialName("message.updated")
data class MessageUpdated(val properties: Props) : BusEvent {
    @Serializable
    data class Props(val info: MessageInfo)
}

@Serializable
@SerialName("message.part.updated")
data class MessagePartUpdated(val properties: Props) : BusEvent {
    @Serializable
    data class Props(
        val part: Part,
        val sessionID: String? = null,
        val messageID: String? = null,
    )
}

@Serializable
@SerialName("message.part.removed")
data class MessagePartRemoved(val properties: Props) : BusEvent {
    @Serializable
    data class Props(
        val sessionID: String? = null,
        val messageID: String? = null,
        val partID: String? = null,
    )
}

@Serializable
@SerialName("message.removed")
data class MessageRemoved(val properties: Props) : BusEvent {
    @Serializable
    data class Props(
        val sessionID: String? = null,
        val messageID: String? = null,
    )
}

@Serializable
@SerialName("session.idle")
data class SessionIdle(val properties: Props) : BusEvent {
    @Serializable
    data class Props(val sessionID: String? = null)
}

@Serializable
@SerialName("session.error")
data class SessionError(val properties: Props) : BusEvent {
    @Serializable
    data class Props(
        val sessionID: String? = null,
        val error: kotlinx.serialization.json.JsonElement? = null,
    )
}

@Serializable
@SerialName("session.updated")
data class SessionUpdated(val properties: Props) : BusEvent {
    @Serializable
    data class Props(val info: Session)
}

@Serializable
@SerialName("session.deleted")
data class SessionDeleted(val properties: Props) : BusEvent {
    @Serializable
    data class Props(val info: Session? = null, val sessionID: String? = null)
}

@Serializable
@SerialName("permission.updated")
data class PermissionUpdated(val properties: Permission) : BusEvent

@Serializable
@SerialName("permission.replied")
data class PermissionReplied(val properties: Props) : BusEvent {
    @Serializable
    data class Props(
        val sessionID: String? = null,
        val permissionID: String? = null,
        val response: String? = null,
    )
}

/** Fallback for any event type we don't model. */
@Serializable
@SerialName("__unknown")
data object UnknownEvent : BusEvent
