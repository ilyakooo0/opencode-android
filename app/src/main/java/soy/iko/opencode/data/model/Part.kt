package soy.iko.opencode.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A message part — a discriminated union on `type` (the global class discriminator).
 * Unknown types decode to [UnknownPart] via the registered polymorphic default,
 * so the stream survives server additions.
 *
 * Every part carries [id] plus its parent [messageID]/[sessionID], which the reducer
 * uses to attach streamed parts to the right message.
 */
@Serializable
sealed interface Part {
    val id: String
    val messageID: String?
    val sessionID: String?
}

@Serializable
@SerialName("text")
data class TextPart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
    val text: String = "",
    val synthetic: Boolean = false,
    val ignored: Boolean = false,
    val time: TimeInfo? = null,
) : Part

@Serializable
@SerialName("reasoning")
data class ReasoningPart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
    val text: String = "",
    val time: TimeInfo? = null,
) : Part

@Serializable
@SerialName("tool")
data class ToolPart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
    val callID: String = "",
    val tool: String = "",
    val state: ToolState = ToolPending,
) : Part

@Serializable
@SerialName("file")
data class FilePart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
    val source: String? = null,
) : Part

@Serializable
@SerialName("step-start")
data class StepStartPart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
) : Part

@Serializable
@SerialName("step-finish")
data class StepFinishPart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
) : Part

/** Fallback for an unrecognized `type`. */
@Serializable
@SerialName("__unknown")
data class UnknownPart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
) : Part
