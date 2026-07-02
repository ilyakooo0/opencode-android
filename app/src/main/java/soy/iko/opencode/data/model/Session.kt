package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** A session as returned by `GET /session` and `POST /session`. */
@Immutable
@Serializable
data class Session(
    val id: String,
    val parentID: String? = null,
    val title: String? = null,
    val version: String? = null,
    val time: TimeInfo? = null,
    // The absolute worktree/directory the agent runs in for this session. Set server-side
    // from the `directory` query param at creation; persisted per session so the client
    // needn't re-send it on follow-up calls (see OpencodeApiClient.createSession).
    val directory: String? = null,
    val projectID: String? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled session"

    /** A compact form of [directory] for list/subtitle display: the last path segment,
     *  or the full path when it has no separators. Null/blank when no directory is set. */
    val displayDirectory: String?
        get() = directory?.takeIf { it.isNotBlank() }?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
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
