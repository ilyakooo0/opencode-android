package soy.iko.opencode.ui

/** String routes for the app's NavHost. Kept simple and centralized. */
object Routes {
    const val SERVERS = "servers"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val USAGE = "usage"
    const val MCP = "mcp"
    const val FILES = "files"
    const val SEARCH = "search"

    const val SERVER_EDIT = "server_edit"
    fun serverEdit(id: String? = null) =
        if (id.isNullOrEmpty()) SERVER_EDIT else "$SERVER_EDIT?id=${android.net.Uri.encode(id)}"

    /** Open the editor seeded with [sourceId]'s fields but creating a new profile. */
    fun serverEditDuplicate(sourceId: String) = "$SERVER_EDIT?dup=${android.net.Uri.encode(sourceId)}"

    const val CHAT = "chat"
    fun chat(sessionId: String, focusMessageId: String? = null): String {
        val base = "$CHAT/${android.net.Uri.encode(sessionId)}"
        // An optional focusMessageId scrolls the chat to (and briefly highlights) that message,
        // used by global search to jump directly to the matched message instead of the tail.
        return if (focusMessageId != null && focusMessageId.isNotBlank()) {
            "$base?focus=${android.net.Uri.encode(focusMessageId)}"
        } else {
            base
        }
    }

    const val FILE_VIEW = "file_view"
    fun fileView(path: String, line: Int? = null): String {
        val base = "$FILE_VIEW?path=${android.net.Uri.encode(path)}"
        // A positive [line] scrolls the viewer to that 1-based line (from a search hit).
        return if (line != null && line > 0) "$base&line=$line" else base
    }
}
