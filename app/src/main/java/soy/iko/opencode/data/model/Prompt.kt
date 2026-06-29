package soy.iko.opencode.data.model

import kotlinx.serialization.Serializable

/** Body for `POST /session/:id/message`. */
@Serializable
data class PromptRequest(
    val parts: List<PromptPart>,
    val model: ModelRef? = null,
    val agent: String? = null,
)

/** Input parts are a small subset of [Part]; for the scaffold we send text. */
@Serializable
data class PromptPart(
    val type: String = "text",
    val text: String,
)

/** Body for `POST /session/:id/command`. */
@Serializable
data class CommandRequest(
    val command: String,
    val arguments: String = "",
    val agent: String? = null,
)
