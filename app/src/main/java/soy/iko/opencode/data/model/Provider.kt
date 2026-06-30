package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Response of `GET /config/providers`. */
@Immutable
@Serializable
data class ProvidersResponse(
    val providers: List<Provider> = emptyList(),
    val default: Map<String, String> = emptyMap(),
)

@Immutable
@Serializable
data class Provider(
    val id: String,
    val name: String? = null,
    val models: Map<String, ModelInfo> = emptyMap(),
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id
}

@Immutable
@Serializable
data class ModelInfo(
    val id: String? = null,
    val name: String? = null,
) {
    fun displayName(modelKey: String): String =
        name?.takeIf { it.isNotBlank() } ?: id ?: modelKey
}

/** A flattened provider/model selection used by the prompt body and the model picker. */
@Immutable
@Serializable
data class ModelRef(
    val providerID: String,
    val modelID: String,
)

/** A single selectable entry in the model picker, flattened from [ProvidersResponse]. */
@Immutable
data class ModelOption(
    val providerID: String,
    val modelID: String,
    val providerLabel: String,
    val modelLabel: String,
) {
    val ref: ModelRef get() = ModelRef(providerID, modelID)
}

/** Flatten providers into a sorted list of pickable options. */
fun ProvidersResponse.toOptions(): List<ModelOption> =
    providers.flatMap { provider ->
        provider.models.map { (modelKey, info) ->
            ModelOption(
                providerID = provider.id,
                modelID = info.id ?: modelKey,
                providerLabel = provider.displayName,
                modelLabel = info.displayName(modelKey),
            )
        }
    }.sortedWith(compareBy({ it.providerLabel.lowercase() }, { it.modelLabel.lowercase() }))

/** The default option to preselect: the server's default for some provider, else the first model. */
fun ProvidersResponse.defaultOption(options: List<ModelOption> = toOptions()): ModelOption? {
    default.entries.firstOrNull()?.let { (providerID, modelID) ->
        options.firstOrNull { it.providerID == providerID && it.modelID == modelID }?.let { return it }
    }
    return options.firstOrNull()
}
