package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

@Immutable
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

@Immutable
@Serializable
@SerialName("reasoning")
data class ReasoningPart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
    val text: String = "",
    val time: TimeInfo? = null,
) : Part

@Immutable
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

@Immutable
@Serializable
@SerialName("file")
data class FilePart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
    // opencode's message-v2 schema sends `source` as a structured FilePartSource OBJECT
    // ({ type, path, text, ... }), not a string, for @-file mentions. Typing it as String
    // threw a SerializationException on decode — which the SSE path silently swallows, so the
    // whole part vanished. Keep it as a tolerant JsonElement (mirroring Permission.pattern) and
    // read the path via [sourcePath]; a bare-string source is still tolerated.
    val source: JsonElement? = null,
) : Part

/** The file path referenced by a [FilePart.source]. opencode sends `source` as a FilePartSource
 *  object whose `path` field holds the file path; older/other shapes may send a bare string.
 *  Returns null when there's no usable path. */
val FilePart.sourcePath: String?
    get() = when (val s = source) {
        is JsonPrimitive -> if (s.isString) s.content else null
        is JsonObject -> (s["path"] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
        else -> null
    }

@Immutable
@Serializable
@SerialName("step-start")
data class StepStartPart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
) : Part

@Immutable
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
@Immutable
@Serializable
@SerialName("__unknown")
data class UnknownPart(
    override val id: String = "",
    override val messageID: String? = null,
    override val sessionID: String? = null,
) : Part
