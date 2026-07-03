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
    // Defaulted so one id-less element in GET /config/providers doesn't fail the whole list decode
    // (emptying the model picker) — matches MessageInfo's defaulted id and the resilient-decoding
    // philosophy. A blank id is degraded but recoverable; a thrown decode is not.
    val id: String = "",
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
    }.sortedWith(
        // CASE_INSENSITIVE_ORDER compares in place; compareBy { it.x.lowercase() } would
        // allocate a fresh lowercased string on every comparison (O(N log N) allocations).
        compareBy<ModelOption, String>(String.CASE_INSENSITIVE_ORDER) { it.providerLabel }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.modelLabel },
    )

/** The default option to preselect: the server's default for some provider, else the first model. */
fun ProvidersResponse.defaultOption(options: List<ModelOption> = toOptions()): ModelOption? {
    // Try each configured provider default in turn and take the first whose model actually
    // survives in `options` (the list may be filtered). Checking only the first `default` entry
    // would mispreselect an arbitrary first option whenever that one provider's default model
    // isn't present, ignoring the other configured defaults.
    for ((providerID, modelID) in default) {
        options.firstOrNull { it.providerID == providerID && it.modelID == modelID }?.let { return it }
    }
    return options.firstOrNull()
}
