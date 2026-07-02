package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A project known to the server, from `GET /project`. A project corresponds to a git
 * worktree root; [worktree] is the absolute path the agent can run in. Used to populate
 * the directory picker when creating a session in a specific directory.
 */
@Immutable
@Serializable
data class Project(
    val id: String = "",
    val worktree: String = "",
    val vcs: String? = null,
    val time: TimeInfo? = null,
)

/**
 * The server's current path info, from `GET /path`. [directory] is the working directory
 * a request with no `directory` override resolves to (the server's launch cwd), shown in
 * the picker as the "server default" option.
 */
@Immutable
@Serializable
data class PathInfo(
    val directory: String? = null,
    val worktree: String? = null,
    val state: String? = null,
    val config: String? = null,
)
