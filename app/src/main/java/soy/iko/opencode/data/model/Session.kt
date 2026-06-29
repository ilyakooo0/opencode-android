package soy.iko.opencode.data.model

import kotlinx.serialization.Serializable

/** A session as returned by `GET /session` and `POST /session`. */
@Serializable
data class Session(
    val id: String,
    val parentID: String? = null,
    val title: String? = null,
    val version: String? = null,
    val time: TimeInfo? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled session"
}

/** Body for `POST /session`. */
@Serializable
data class CreateSessionRequest(
    val parentID: String? = null,
    val title: String? = null,
)

/** Body for `PATCH /session/:id`. */
@Serializable
data class UpdateSessionRequest(
    val title: String? = null,
)
