package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Entry from `GET /file?path=` (directory listing). */
@Immutable
@Serializable
data class FileNode(
    val name: String = "",
    val path: String = "",
    val type: String? = null,
    // Size in bytes and last-modified epoch-ms. The server doesn't currently emit these, so
    // they default to null and the browser renders nothing extra; optional + defaulted keeps
    // the client forward-compatible (resilient decoding) so the moment the server adds them
    // the listing shows size/date with no client change.
    val size: Long? = null,
    val mtime: Long? = null,
) {
    val isDirectory: Boolean get() = type == "directory"
}

/** Response of `GET /file/content?path=`. */
@Immutable
@Serializable
data class FileContent(
    val type: String? = null, // "text" | "binary"
    val content: String = "",
    val diff: String? = null,
    val mimeType: String? = null,
    val encoding: String? = null,
) {
    val isBinary: Boolean get() = type == "binary" || encoding == "base64"
}

/** Entry from `GET /file/status` (VCS status). */
@Immutable
@Serializable
data class FileStatusEntry(
    val path: String = "",
    val added: Int = 0,
    val removed: Int = 0,
    val status: String? = null, // "added" | "deleted" | "modified"
)
