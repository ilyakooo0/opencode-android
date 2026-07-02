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
import soy.iko.opencode.data.model.PathInfo
import soy.iko.opencode.data.model.FilePromptPart
import soy.iko.opencode.data.model.FindMatch
import soy.iko.opencode.data.model.PermissionReplyBody
import soy.iko.opencode.data.model.PermissionResponse
import soy.iko.opencode.data.model.Project
import soy.iko.opencode.data.model.PromptPart
import soy.iko.opencode.data.model.PromptRequest
import soy.iko.opencode.data.model.ProvidersResponse
import soy.iko.opencode.data.model.RevertRequest
import soy.iko.opencode.data.model.Session
import soy.iko.opencode.data.model.ShellRequest
import soy.iko.opencode.data.model.SummarizeRequest
import soy.iko.opencode.data.model.SymbolResult
import soy.iko.opencode.data.model.TextPromptPart
import soy.iko.opencode.data.model.UpdateSessionRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import java.net.URLEncoder

/**
 * Request/response wrapper over the opencode REST endpoints. The long-lived `/event`
 * SSE stream lives in [EventStreamClient]; everything else is here.
 */
open class OpencodeApiClient private constructor(
    private val client: HttpClient?,
    @Suppress("unused") private val testMode: Boolean,
) {
    constructor(client: HttpClient) : this(client, false)
    protected constructor() : this(null, true)

    // --- Catalog cache (providers/agents/commands) ---
    // These change rarely, but the ChatViewModel re-fetches them on every creation.
    // A short-lived in-memory cache avoids redundant network calls when navigating
    // between sessions on the same server.
    private data class CachedEntry<T>(val value: T, val fetchedAt: Long)
    // Per-catalog mutexes so a slow fetch of one catalog doesn't block the others.
    // The lock is held during the fetch to prevent a thundering-herd stampede when
    // multiple coroutines miss the cache simultaneously.
    private val providersMutex = Mutex()
    private val agentsMutex = Mutex()
    private val commandsMutex = Mutex()
    private var cachedProviders: CachedEntry<ProvidersResponse>? = null
    private var cachedAgents: CachedEntry<List<Agent>>? = null
    private var cachedCommands: CachedEntry<List<Command>>? = null

    /** Lightweight connectivity check. Throws on non-2xx / network failure. */
    open suspend fun ping() {
        withRetry { client!!.get("global/health").body<String>() }
    }

    open suspend fun listSessions(): List<Session> = withRetry {
        client!!.get("session").body()
    }

    /**
     * Create a session, optionally in a specific worktree [directory]. The directory is a
     * query parameter (not a body field): the server records it on the session and resolves
     * it by session id thereafter, so follow-up calls (messages, commands, abort, …) don't
     * — and shouldn't — re-send it. A null/blank [directory] lets the server fall back to
     * its own launch cwd, preserving the pre-directory behavior.
     */
    open suspend fun createSession(title: String? = null, directory: String? = null): Session {
        // Generate the idempotency key before entering withRetry so every retry attempt
        // shares it. Without this, a POST that reached the server but whose response was
        // lost (reset/timeout) would be retried and create a second orphan session.
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        val dir = directory?.takeIf { it.isNotBlank() }
        return withRetry {
            client!!.post("session") {
                contentType(ContentType.Application.Json)
                header("Idempotency-Key", idempotencyKey)
                if (dir != null) parameter("directory", dir)
                setBody(CreateSessionRequest(title = title))
            }.body()
        }
    }

    /** List the projects (worktree roots) the server knows about, for the directory picker. */
    open suspend fun listProjects(): List<Project> = withRetry {
        client!!.get("project").body()
    }

    /** The server's current path info; its [PathInfo.directory] is the default (cwd) a
     *  session with no directory override runs in. */
    open suspend fun currentPath(): PathInfo = withRetry {
        client!!.get("path").body()
    }

    open suspend fun updateSession(id: String, title: String): Session = withRetry {
        client!!.patch("session/${encode(id)}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateSessionRequest(title = title))
        }.body()
    }

    open suspend fun deleteSession(id: String) {
        try {
            withRetry { client!!.delete("session/${encode(id)}").body<String>() }
        } catch (t: ClientRequestException) {
            // A DELETE that succeeded but whose response was lost gets retried; the retry
            // hits an already-gone session and returns 404. Deleting a non-existent session
            // is effectively success, so swallow 404 rather than surfacing a spurious failure.
            if (t.response.status.value != 404) throw t
        }
    }

    open suspend fun listMessages(sessionId: String): List<MessageWithParts> = withRetry {
        client!!.get("session/${encode(sessionId)}/message").body()
    }

    open suspend fun sendPrompt(
        sessionId: String,
        text: String,
        attachments: List<FilePromptPart> = emptyList(),
        model: ModelRef? = null,
        agent: String? = null,
    ): MessageWithParts {
        // Generate the idempotency key *before* entering withRetry so all retry
        // attempts share the same key. If the key were generated inside withRetry,
        // each attempt would get a fresh UUID and the server couldn't deduplicate a
        // request that reached it but whose response was lost (e.g. timeout).
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        // A text part is only included when there's actual text, so an image-only prompt
        // (attachments, empty text) doesn't send an empty text part. Attachments follow
        // the text so the model reads the instruction first.
        val parts = buildList<PromptPart> {
            if (text.isNotEmpty()) add(TextPromptPart(text))
            addAll(attachments)
        }
        return withRetry {
            client!!.post("session/${encode(sessionId)}/message") {
                contentType(ContentType.Application.Json)
                header("Idempotency-Key", idempotencyKey)
                setBody(
                    PromptRequest(
                        parts = parts,
                        model = model,
                        agent = agent,
                    ),
                )
            }.body()
        }
    }

    open suspend fun abort(sessionId: String) {
        withRetry { client!!.post("session/${encode(sessionId)}/abort").body<String>() }
    }

    /** Invoke a slash-command by name via `POST /session/:id/command`. */
    open suspend fun runCommand(
        sessionId: String,
        command: String,
        arguments: String = "",
        agent: String? = null,
    ): MessageWithParts {
        // Generate the idempotency key before entering withRetry so all retry
        // attempts share the same key, preventing duplicate command execution if
        // the server processed the request but the response was lost.
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        return withRetry {
            client!!.post("session/${encode(sessionId)}/command") {
                contentType(ContentType.Application.Json)
                header("Idempotency-Key", idempotencyKey)
                setBody(CommandRequest(command = command, arguments = arguments, agent = agent))
            }.body()
        }
    }

    /** Revert the session to just before [messageId] (optionally part [partId]); the
     *  server hides everything after the checkpoint. Returns the updated session. Naturally
     *  idempotent (reverting to the same point twice yields the same state). */
    open suspend fun revert(sessionId: String, messageId: String, partId: String? = null): Session = withRetry {
        client!!.post("session/${encode(sessionId)}/revert") {
            contentType(ContentType.Application.Json)
            setBody(RevertRequest(messageID = messageId, partID = partId))
        }.body()
    }

    /** Undo the active revert checkpoint, restoring the hidden messages. */
    open suspend fun unrevert(sessionId: String): Session = withRetry {
        client!!.post("session/${encode(sessionId)}/unrevert").body()
    }

    /** Create a public share link for the session. Returns the session with [Session.share] set. */
    open suspend fun shareSession(sessionId: String): Session = withRetry {
        client!!.post("session/${encode(sessionId)}/share").body()
    }

    /** Revoke the session's public share link. Returns the session with share cleared. */
    open suspend fun unshareSession(sessionId: String): Session = withRetry {
        client!!.delete("session/${encode(sessionId)}/share").body()
    }

    /** Ask the agent to summarize/compact the conversation to reclaim context. The compacted
     *  summary streams back via SSE like any run; this just triggers it. */
    open suspend fun summarize(sessionId: String, model: ModelRef) {
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        withRetry {
            client!!.post("session/${encode(sessionId)}/summarize") {
                contentType(ContentType.Application.Json)
                header("Idempotency-Key", idempotencyKey)
                setBody(SummarizeRequest(providerID = model.providerID, modelID = model.modelID))
            }.body<String>()
        }
    }

    /** Analyze the project and (re)generate its AGENTS.md. The body is omitted so the server
     *  assigns the message id and uses its default model; the run streams back via SSE. */
    open suspend fun initSession(sessionId: String) {
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        withRetry {
            client!!.post("session/${encode(sessionId)}/init") {
                header("Idempotency-Key", idempotencyKey)
            }.body<String>()
        }
    }

    /** Run a one-off shell [command] in the session's worktree. Output streams back via SSE.
     *  [agent] scopes the run (the server requires it). */
    open suspend fun shell(sessionId: String, command: String, agent: String, model: ModelRef? = null) {
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        withRetry {
            client!!.post("session/${encode(sessionId)}/shell") {
                contentType(ContentType.Application.Json)
                header("Idempotency-Key", idempotencyKey)
                setBody(ShellRequest(agent = agent, command = command, model = model))
            }.body<String>()
        }
    }

    open suspend fun providers(): ProvidersResponse = providersMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedProviders
        if (cached != null && now - cached.fetchedAt < NetworkConfig.catalogCacheTtlMs) {
            return@withLock cached.value
        }
        val value = withRetry { client!!.get("config/providers").body<ProvidersResponse>() }
        cachedProviders = CachedEntry(value, System.currentTimeMillis())
        value
    }

    open suspend fun agents(): List<Agent> = agentsMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedAgents
        if (cached != null && now - cached.fetchedAt < NetworkConfig.catalogCacheTtlMs) {
            return@withLock cached.value
        }
        val value = withRetry { client!!.get("agent").body<List<Agent>>() }
        cachedAgents = CachedEntry(value, System.currentTimeMillis())
        value
    }

    open suspend fun commands(): List<Command> = commandsMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedCommands
        if (cached != null && now - cached.fetchedAt < NetworkConfig.catalogCacheTtlMs) {
            return@withLock cached.value
        }
        val value = withRetry { client!!.get("command").body<List<Command>>() }
        cachedCommands = CachedEntry(value, System.currentTimeMillis())
        value
    }

    /** Invalidate the catalog cache (e.g. on server switch). */
    open suspend fun invalidateCache() {
        providersMutex.withLock { cachedProviders = null }
        agentsMutex.withLock { cachedAgents = null }
        commandsMutex.withLock { cachedCommands = null }
    }

    open suspend fun invalidateProvidersCache() {
        providersMutex.withLock { cachedProviders = null }
    }

    open suspend fun invalidateAgentsCache() {
        agentsMutex.withLock { cachedAgents = null }
    }

    open suspend fun invalidateCommandsCache() {
        commandsMutex.withLock { cachedCommands = null }
    }

    /** Respond to a permission request so a paused tool run can proceed. */
    open suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
    ): String = try {
        withRetry {
            client!!.post("session/${encode(sessionId)}/permissions/${encode(permissionId)}") {
                contentType(ContentType.Application.Json)
                setBody(PermissionReplyBody(response.wire))
            }.body<String>()
        }
    } catch (t: ClientRequestException) {
        // A response that reached the server but whose reply was lost gets retried; the
        // retry finds the permission already resolved (404 gone / 409 conflict). Both mean
        // the answer landed, so treat them as success instead of a spurious failure.
        val code = t.response.status.value
        if (code != 404 && code != 409) throw t
        ""
    }

    // --- Files ---

    open suspend fun findFiles(query: String): List<String> = withRetry {
        client!!.get("find/file") { parameter("query", query) }.body()
    }

    /** Ripgrep content search across the project (`GET /find?pattern=`). */
    open suspend fun findText(pattern: String): List<FindMatch> = withRetry {
        client!!.get("find") { parameter("pattern", pattern) }.body()
    }

    /** LSP workspace symbol search (`GET /find/symbol?query=`). */
    open suspend fun findSymbol(query: String): List<SymbolResult> = withRetry {
        client!!.get("find/symbol") { parameter("query", query) }.body()
    }

    open suspend fun listDirectory(path: String): List<FileNode> = withRetry {
        client!!.get("file") { parameter("path", path) }.body()
    }

    open suspend fun readFile(path: String): FileContent = withRetry {
        client!!.get("file/content") { parameter("path", path) }.body()
    }

    open suspend fun fileStatus(): List<FileStatusEntry> = withRetry {
        client!!.get("file/status").body()
    }
}

private suspend fun <T> withRetry(
    maxAttempts: Int = NetworkConfig.retryMaxAttempts,
    initialDelayMs: Long = NetworkConfig.retryInitialDelayMs,
    block: suspend () -> T,
): T = withRetryInternal(maxAttempts, initialDelayMs, jitterFactor = NetworkConfig.retryJitterFactor, block)

/**
 * Retry a suspending block up to [maxAttempts] times with exponential backoff.
 * Cancellation exceptions are re-thrown immediately. Client errors (4xx) are
 * non-retryable — a 401 or 404 won't succeed on retry, so they surface immediately
 * instead of wasting up to 3.5 s of backoff. Network failures, timeouts, and 5xx
 * server errors are retried. Exposed as `internal` so the retry/skip rules are
 * unit-testable without an HTTP server.
 */
internal suspend fun <T> withRetryInternal(
    maxAttempts: Int = NetworkConfig.retryMaxAttempts,
    initialDelayMs: Long = NetworkConfig.retryInitialDelayMs,
    jitterFactor: Double = NetworkConfig.retryJitterFactor,
    block: suspend () -> T,
): T {
    var lastError: Throwable? = null
    for (attempt in 1..maxAttempts) {
        try {
            return block()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: ClientRequestException) {
            // 429 (Too Many Requests) is transient — retry with backoff.
            // All other 4xx are non-retryable.
            if (t.response.status.value != 429) throw t
            lastError = t
            if (attempt < maxAttempts) {
                delay(jitteredBackoff(attempt, initialDelayMs, jitterFactor))
            }
        } catch (t: ServerResponseException) {
            // 5xx server errors are retryable with backoff.
            lastError = t
            if (attempt < maxAttempts) {
                delay(jitteredBackoff(attempt, initialDelayMs, jitterFactor))
            }
        } catch (t: ResponseException) {
            // 3xx (redirect limit exceeded) is non-retryable.
            throw t
        } catch (t: Exception) {
            lastError = t
            if (attempt < maxAttempts) {
                delay(jitteredBackoff(attempt, initialDelayMs, jitterFactor))
            }
        }
    }
    throw lastError ?: IllegalStateException("withRetry failed without error")
}

/** Computes an exponential backoff delay with symmetric jitter. Exposed as `internal`
 *  so the backoff formula is unit-testable without an HTTP server. */
internal fun jitteredBackoff(
    attempt: Int,
    initialDelayMs: Long,
    jitterFactor: Double,
): Long {
    // Guard against Int overflow from the shift and Long overflow from
    // the multiplication at high attempt counts.
    val shift = (attempt - 1).coerceAtMost(30)
    val baseDelay = (initialDelayMs * (1L shl shift)).coerceAtMost(NetworkConfig.retryMaxDelayMs)
    // Symmetric jitter: vary by ±jitterFactor so the delay is sometimes
    // shorter, sometimes longer — spreads concurrent client retries and
    // prevents thundering-herd reconnection storms.
    val jitter = ((baseDelay * jitterFactor) * (Random.nextDouble() * 2 - 1)).toLong()
    return (baseDelay + jitter).coerceAtLeast(0)
}

/** URL-encode a path segment, replacing + with %20 (URLEncoder uses query-param encoding). */
private fun encode(segment: String): String =
    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
