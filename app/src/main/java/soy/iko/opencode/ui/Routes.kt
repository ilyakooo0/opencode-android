package soy.iko.opencode.ui

/** String routes for the app's NavHost. Kept simple and centralized. */
object Routes {
    const val SERVERS = "servers"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val FILES = "files"

    const val SERVER_EDIT = "server_edit"
    fun serverEdit(id: String? = null) =
        if (id.isNullOrEmpty()) SERVER_EDIT else "$SERVER_EDIT?id=$id"

    const val CHAT = "chat"
    fun chat(sessionId: String) = "$CHAT/$sessionId"

    const val FILE_VIEW = "file_view"
    fun fileView(path: String) = "$FILE_VIEW?path=${android.net.Uri.encode(path)}"
}
