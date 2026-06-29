package soy.iko.opencode.data.network

import soy.iko.opencode.data.model.Agent
import soy.iko.opencode.data.model.Command
import soy.iko.opencode.data.model.CommandRequest
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
import kotlinx.coroutines.delay

/**
 * Request/response wrapper over the opencode REST endpoints. The long-lived `/event`
 * SSE stream lives in [EventStreamClient]; everything else is here.
 */
class OpencodeApiClient(private val client: HttpClient) {

    /** Lightweight connectivity check. Throws on non-2xx / network failure. */
    suspend fun ping() {
        client.get("global/health")
    }

    suspend fun listSessions(): List<Session> = withRetry {
        client.get("session").body()
    }

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

    suspend fun listMessages(sessionId: String): List<MessageWithParts> = withRetry {
        client.get("session/$sessionId/message").body()
    }

    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        model: ModelRef? = null,
        agent: String? = null,
    ): MessageWithParts = withRetry {
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
    }

    suspend fun abort(sessionId: String) {
        client.post("session/$sessionId/abort")
    }

    /** Invoke a slash-command by name via `POST /session/:id/command`. */
    suspend fun runCommand(
        sessionId: String,
        command: String,
        arguments: String = "",
        agent: String? = null,
    ): MessageWithParts =
        client.post("session/$sessionId/command") {
            contentType(ContentType.Application.Json)
            setBody(CommandRequest(command = command, arguments = arguments, agent = agent))
        }.body()

    suspend fun providers(): ProvidersResponse = withRetry {
        client.get("config/providers").body()
    }

    suspend fun agents(): List<Agent> = withRetry {
        client.get("agent").body()
    }

    suspend fun commands(): List<Command> = withRetry {
        client.get("command").body()
    }

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

    suspend fun findFiles(query: String): List<String> = withRetry {
        client.get("find/file") { parameter("query", query) }.body()
    }

    suspend fun listDirectory(path: String): List<FileNode> = withRetry {
        client.get("file") { parameter("path", path) }.body()
    }

    suspend fun readFile(path: String): FileContent = withRetry {
        client.get("file/content") { parameter("path", path) }.body()
    }

    suspend fun fileStatus(): List<FileStatusEntry> = withRetry {
        client.get("file/status").body()
    }
}

/**
 * Retry a suspending block up to [maxAttempts] times with exponential backoff.
 * Cancellation exceptions are re-thrown immediately. Non-2xx HTTP responses throw
 * (because [HttpClientFactory] sets `expectSuccess = true`) and are retried.
 */
private suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 500L,
    block: suspend () -> T,
): T {
    var lastError: Throwable? = null
    for (attempt in 1..maxAttempts) {
        try {
            return block()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            lastError = t
            if (attempt < maxAttempts) delay(initialDelayMs * (1 shl (attempt - 1)))
        }
    }
    throw lastError ?: IllegalStateException("withRetry failed without error")
}
