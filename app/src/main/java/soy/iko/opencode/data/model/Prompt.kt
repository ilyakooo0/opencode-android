package soy.iko.opencode.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body for `POST /session/:id/message`. */
@Serializable
data class PromptRequest(
    val parts: List<PromptPart>,
    val model: ModelRef? = null,
    val agent: String? = null,
)

/**
 * An input part for a prompt — a discriminated union on `type` (the global class
 * discriminator). We compose text and file (image/document attachment) parts; the server
 * models more (agent, subtask) that this client doesn't send.
 */
@Serializable
sealed interface PromptPart

@Serializable
@SerialName("text")
data class TextPromptPart(val text: String) : PromptPart

/**
 * A file/image attachment. [url] must be a data URL carrying the base64 payload —
 * `data:<mime>;base64,<bytes>` — which is how the server embeds media for the model to
 * see (see opencode's message-v2.ts). [mime] must match the payload; the server forwards
 * it as the model's mediaType.
 */
@Serializable
@SerialName("file")
data class FilePromptPart(
    val mime: String,
    val url: String,
    val filename: String? = null,
) : PromptPart

/** Body for `POST /session/:id/command`. */
@Serializable
data class CommandRequest(
    val command: String,
    val arguments: String = "",
    val agent: String? = null,
)

/** Body for `POST /session/:id/revert`. */
@Serializable
data class RevertRequest(
    val messageID: String,
    val partID: String? = null,
)

/** Body for `POST /session/:id/summarize`. */
@Serializable
data class SummarizeRequest(
    val providerID: String,
    val modelID: String,
)

/** Body for `POST /session/:id/shell`. */
@Serializable
data class ShellRequest(
    val agent: String,
    val command: String,
    val model: ModelRef? = null,
)
