package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Entry from `GET /agent`. */
@Immutable
@Serializable
data class Agent(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val builtIn: Boolean = false,
    val prompt: String? = null,
) {
    val isPrimary: Boolean get() = mode == "primary"
    val displayDescription: String get() = description?.takeIf { it.isNotBlank() } ?: "Custom agent"
}

/** Entry from `GET /command`. */
@Immutable
@Serializable
data class Command(
    val name: String,
    val description: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val template: String,
    val subtask: Boolean = false,
) {
    val displayDescription: String get() = description?.takeIf { it.isNotBlank() } ?: template.take(80)
}
