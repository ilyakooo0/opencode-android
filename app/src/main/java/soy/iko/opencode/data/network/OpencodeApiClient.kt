package soy.iko.opencode.data.network

import soy.iko.opencode.data.model.CreateSessionRequest
import soy.iko.opencode.data.model.FileContent
import soy.iko.opencode.data.model.FileNode
import soy.iko.opencode.data.model.FileStatusEntry
import soy.iko.opencode.data.model.MessageWithParts
import soy.iko.opencode.data.model.ModelRef
import soy.iko.opencode.data.model.PermissionReplyBody
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.model.PromptPart
import soy.iko.opencode.data.model.PromptRequest
import soy.iko.opencode.data.model.ProvidersResponse
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.UpdateSessionRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Request/response wrapper over the opencode REST endpoints. The long-lived `/event`
 * SSE stream lives in [EventStreamClient]; everything else is here.
 */
class OpencodeApiClient(private val client: HttpClient) {

    /** Lightweight connectivity check used by the connect screen. */
    suspend fun ping(): Boolean {
        client.get("app")
        return true
    }

    suspend fun listSessions(): List<Session> =
        client.get("session").body()

    suspend fun createSession(title: String? = null): Session =
        client.post("session") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(title = title))
        }.body()

    suspend fun updateSession(id: String, title: String): Session =
        client.patch("session/$id") {
            contentType(ContentType.Application.Json)
            setBody(UpdateSessionRequest(title = title))
        }.body()

    suspend fun deleteSession(id: String) {
        client.delete("session/$id")
    }

    suspend fun listMessages(sessionId: String): List<MessageWithParts> =
        client.get("session/$sessionId/message").body()

    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        model: ModelRef? = null,
        agent: String? = null,
    ): MessageWithParts =
        client.post("session/$sessionId/message") {
            contentType(ContentType.Application.Json)
            setBody(
                PromptRequest(
                    parts = listOf(PromptPart(text = text)),
                    model = model,
                    agent = agent,
                ),
            )
        }.body()

    suspend fun abort(sessionId: String) {
        client.post("session/$sessionId/abort")
    }

    suspend fun providers(): ProvidersResponse =
        client.get("config/providers").body()

    /** Respond to a permission request so a paused tool run can proceed. */
    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
    ) {
        client.post("session/$sessionId/permissions/$permissionId") {
            contentType(ContentType.Application.Json)
            setBody(PermissionReplyBody(response.wire))
        }
    }

    // --- Files ---

    suspend fun findFiles(query: String): List<String> =
        client.get("find/file") { parameter("query", query) }.body()

    suspend fun listDirectory(path: String): List<FileNode> =
        client.get("file") { parameter("path", path) }.body()

    suspend fun readFile(path: String): FileContent =
        client.get("file/content") { parameter("path", path) }.body()

    suspend fun fileStatus(): List<FileStatusEntry> =
        client.get("file/status").body()
}
