package soy.iko.opencode.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The `info` object of a message. Discriminated on `role`, which differs from the
 * global `type` discriminator used by [Part], so this hierarchy overrides it.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("role")
sealed interface MessageInfo {
    val id: String
    val sessionID: String
    val time: TimeInfo?
}

@Serializable
@SerialName("user")
data class UserMessage(
    override val id: String,
    override val sessionID: String,
    override val time: TimeInfo? = null,
) : MessageInfo

@Serializable
@SerialName("assistant")
data class AssistantMessage(
    override val id: String,
    override val sessionID: String,
    val parentID: String? = null,
    val providerID: String? = null,
    val modelID: String? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val error: JsonObject? = null,
    override val time: TimeInfo? = null,
) : MessageInfo {
    /** A run is complete once the server stamps a completion time. */
    val isComplete: Boolean get() = time?.completed != null
}

/** Fallback for an unrecognized `role`, so new server roles never crash decoding. */
@Serializable
@SerialName("__unknown")
data class UnknownMessage(
    override val id: String = "",
    override val sessionID: String = "",
    override val time: TimeInfo? = null,
) : MessageInfo

/** A message together with its parts — the shape returned by message endpoints and the prompt response. */
@Serializable
data class MessageWithParts(
    val info: MessageInfo,
    val parts: List<Part> = emptyList(),
)
