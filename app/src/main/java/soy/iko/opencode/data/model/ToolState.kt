package soy.iko.opencode.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The state machine of a tool call, discriminated on `status`.
 * pending -> running -> completed | error
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
sealed interface ToolState

@Serializable
@SerialName("pending")
data object ToolPending : ToolState

@Serializable
@SerialName("running")
data class ToolRunning(
    val input: JsonElement? = null,
    val title: String? = null,
    val time: TimeInfo? = null,
) : ToolState

@Serializable
@SerialName("completed")
data class ToolCompleted(
    val input: JsonElement? = null,
    val output: String? = null,
    val title: String? = null,
    val time: TimeInfo? = null,
) : ToolState

@Serializable
@SerialName("error")
data class ToolError(
    val input: JsonElement? = null,
    val error: String? = null,
    val time: TimeInfo? = null,
) : ToolState

/** Fallback for an unrecognized `status`. */
@Serializable
@SerialName("__unknown")
data object ToolUnknown : ToolState
