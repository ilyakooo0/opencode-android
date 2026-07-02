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
    // Present only while the session is shared (POST /session/:id/share); cleared on unshare.
    val share: Share? = null,
    // Present only while a revert checkpoint is active (POST /session/:id/revert); cleared on unrevert.
    val revert: Revert? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled session"

    /** A compact form of [directory] for list/subtitle display: the last path segment,
     *  or the full path when it has no separators. Null/blank when no directory is set. */
    val displayDirectory: String?
        get() = directory?.takeIf { it.isNotBlank() }?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** True while the session has an active public share link. */
    val isShared: Boolean get() = !share?.url.isNullOrBlank()

    /** True while a revert checkpoint is active (messages after it are hidden server-side). */
    val isReverted: Boolean get() = revert != null

    /** Public share link for the session, from `POST /session/:id/share`. */
    @Immutable
    @Serializable
    data class Share(val url: String = "")

    /** The active revert checkpoint, from `POST /session/:id/revert`. */
    @Immutable
    @Serializable
    data class Revert(
        val messageID: String = "",
        val partID: String? = null,
        val snapshot: String? = null,
        val diff: String? = null,
    )
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
