package soy.iko.opencode.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * The application core: a Kotlin port of the Rust Crux core (`shared/`).
 *
 * It owns all state, turns [Event]s into state changes + effects, and exposes an
 * observable [view]. Effects are executed here in the shell: REST calls through
 * [HttpClient] and the long-lived `/event` stream through [SseClient]. Every
 * server-sent event is fed back in and applied incrementally, so assistant
 * replies stream in token-by-token.
 *
 * All state mutation happens on [scope]'s (main) dispatcher; only the blocking
 * network I/O hops to a background thread inside the clients.
 */
class OpencodeCore(
    private val scope: CoroutineScope,
    private val http: HttpClient = HttpClient(),
) {
    // ── Model ────────────────────────────────────────────────────────────────
    private var serverUrl = ""
    private var username = ""
    private var password = ""
    private var authRequired = false
    private var connected = false
    private var loading = false
    private var error: String? = null
    private var screen = Screen.Connect
    private val sessions = mutableListOf<SessionView>()
    private var currentSessionId: String? = null
    private var currentSessionTitle = ""
    private val messages = mutableListOf<MsgState>()
    private var draft = ""
    private var generating = false
    private var sseConnected = true
    private var localSeq = 0L

    private val _view = MutableStateFlow(snapshot())
    val view: StateFlow<UiState> = _view.asStateFlow()

    private var sse: SseClient? = null
    private var sseClosedIntentionally = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    // Guards against overlapping session refreshes: rapid taps launch multiple
    // loadSessions coroutines and the slowest-to-return would otherwise win.
    private var loadSessionsJob: Job? = null
    // Tracks the in-flight createSession coroutine so navigating away can cancel it
    // before it dispatches SelectSession into a screen the user already left.
    private var createSessionJob: Job? = null
    // Tracks the in-flight loadMessages coroutine. Rapidly switching sessions launches
    // several; cancel the prior one so only the newest fetch runs (saves needless requests).
    private var loadMessagesJob: Job? = null
    // Tracks the in-flight probeHealth coroutine. Rapid double-taps on Connect would
    // otherwise launch two concurrent health probes; cancel the prior one first.
    private var connectJob: Job? = null
    // Tracks the in-flight DeleteSession coroutine so navigating away can cancel it
    // before its response handler mutates the sessions list / screen the user already left.
    private var deleteSessionJob: Job? = null
    // Tracks the in-flight sendMessage coroutine so navigating away or switching sessions
    // can cancel it before its response handler touches stale message state.
    private var sendMessageJob: Job? = null
    // Tracks the in-flight abort coroutine (CancelGeneration) so rapid cancel taps don't
    // fire multiple abort requests, and navigating away can cancel it.
    private var abortJob: Job? = null
    // Message IDs removed via SSE; a late MessageUpdated for one must not resurrect it.
    private val removedMessageIds = mutableSetOf<String>()

    // ── Event entry point ──────────────────────────────────────────────────────
    fun dispatch(event: Event) {
        when (event) {
            // Store the field verbatim while typing; normalizing here would fight
            // the user's edits (e.g. eat the "://" as they type it). The URL is
            // normalized once, on Connect.
            // Clear any prior error as soon as the user edits a field, so a stale
            // message (e.g. "Invalid credentials") doesn't linger while they correct it.
            is Event.ServerUrlChanged -> { serverUrl = event.url; if (error != null) error = null; emit() }
            is Event.UsernameChanged -> { username = event.value; if (error != null) error = null; emit() }
            is Event.PasswordChanged -> { password = event.value; if (error != null) error = null; emit() }

            Event.Connect -> {
                serverUrl = normalizeUrl(serverUrl)
                if (serverUrl.isEmpty()) { error = "Enter a server URL"; emit(); return }
                // The auth fields are showing but empty — sending the request now
                // would just 401 again in a loop. Prompt for credentials instead.
                if (authRequired && !hasCredentials()) { error = "Enter username and password"; emit(); return }
                // Leave authRequired as-is; probeHealth sets it from the response (true on
                // 401, false on success). Resetting it here just flickers the auth fields.
                loading = true; error = null; emit()
                connectJob?.cancel()
                connectJob = scope.launch { probeHealth() }
            }

            Event.LoadSessions -> {
                if (!connected) { error = "Not connected"; emit(); return }
                loading = true; emit()
                loadSessionsJob?.cancel()
                loadSessionsJob = scope.launch { loadSessions() }
            }

            is Event.SelectSession -> {
                // The session may have been deleted (e.g. via SSE) while the list was on
                // screen; opening it would only 404 on loadMessages. Bail with an error.
                val session = sessions.firstOrNull { it.id == event.id }
                if (session == null) { error = "Session no longer exists"; emit(); return }
                currentSessionTitle = session.title
                currentSessionId = event.id
                messages.clear()
                removedMessageIds.clear()
                screen = Screen.Chat
                loading = true
                generating = false
                error = null
                emit()
                connectJob?.cancel()
                abortJob?.cancel()
                sendMessageJob?.cancel()
                deleteSessionJob?.cancel()
                loadMessagesJob?.cancel()
                loadMessagesJob = scope.launch { loadMessages(event.id) }
            }

            Event.CreateSession -> {
                if (!connected) { error = "Not connected"; emit(); return }
                loading = true; error = null; emit()
                createSessionJob?.cancel()
                createSessionJob = scope.launch { createSession() }
            }

            is Event.DeleteSession -> {
                if (!connected) { error = "Not connected"; emit(); return }
                val id = event.id
                // Delete on the server first; only drop it from the UI once that
                // succeeds, so a failed request leaves the session recoverable.
                loading = true; error = null; emit()
                deleteSessionJob?.cancel()
                deleteSessionJob = scope.launch {
                    http.delete("$serverUrl/session/$id", authHeader()).fold(
                        onSuccess = { resp ->
                            if (resp.code in 200..299) {
                                sessions.removeAll { it.id == id }
                                if (isCurrent(id)) {
                                    currentSessionId = null
                                    messages.clear()
                                    screen = Screen.Sessions
                                }
                            } else {
                                error = "Failed to delete session"
                            }
                            loading = false
                        },
                        onFailure = {
                            error = "Failed to delete session"
                            loading = false
                        },
                    )
                    emit()
                }
            }

            is Event.DraftChanged -> { draft = event.text; emit() }

            is Event.SendMessage -> sendMessage(event.text)

            Event.CancelGeneration -> {
                generating = false
                messages.forEach { it.streaming = false }
                emit()
                val id = currentSessionId ?: return
                // Debounce rapid cancel taps: cancel any in-flight abort before firing
                // another so we don't stack duplicate abort requests.
                abortJob?.cancel()
                abortJob = scope.launch { http.postJson("$serverUrl/session/$id/abort", "{}", authHeader()) }
            }

            Event.NavigateToSessions -> {
                screen = Screen.Sessions
                currentSessionId = null
                messages.clear()
                removedMessageIds.clear()
                draft = ""
                generating = false
                // Clear any error from the chat (e.g. a failed send) so it doesn't
                // linger on the sessions list after the user navigates back.
                error = null
                loading = true
                emit()
                connectJob?.cancel()
                abortJob?.cancel()
                createSessionJob?.cancel()
                deleteSessionJob?.cancel()
                sendMessageJob?.cancel()
                loadMessagesJob?.cancel()
                loadSessionsJob?.cancel()
                loadSessionsJob = scope.launch { loadSessions() }
            }

            Event.NavigateToConnect -> {
                screen = Screen.Connect
                connected = false
                loading = false
                generating = false
                sessions.clear()
                messages.clear()
                removedMessageIds.clear()
                currentSessionId = null
                draft = ""
                // Reset to the default so a late-firing close callback from the old
                // SSE connection can't flash the reconnecting banner on the next connect.
                sseConnected = true
                connectJob?.cancel()
                abortJob?.cancel()
                createSessionJob?.cancel()
                deleteSessionJob?.cancel()
                sendMessageJob?.cancel()
                loadMessagesJob?.cancel()
                closeSse()
                emit()
            }

            Event.DismissError -> { error = null; emit() }
        }
    }

    /** Reconnect the stream on a fresh process (e.g. after rotation) if already connected. */
    fun resumeStreamingIfConnected() {
        if (connected && sse == null) startSse()
    }

    fun shutdown() {
        // Cancel every tracked coroutine before tearing down the stream so none can
        // emit into a cancelled scope after the ViewModel is cleared.
        connectJob?.cancel()
        loadSessionsJob?.cancel()
        createSessionJob?.cancel()
        loadMessagesJob?.cancel()
        deleteSessionJob?.cancel()
        sendMessageJob?.cancel()
        abortJob?.cancel()
        closeSse()
    }

    // ── Effects ────────────────────────────────────────────────────────────────
    private fun sendMessage(raw: String) {
        val text = raw.trim()
        val session = currentSessionId ?: return
        if (text.isEmpty()) return
        // Debounce rapid double-taps on send: a send is already in flight, so a second
        // tap would create a duplicate user message and fire a duplicate request.
        if (generating) return
        localSeq += 1
        val user = MsgState("local-$localSeq", "user", System.currentTimeMillis()).apply {
            status = MessageStatus.Pending
            textParts["local"] = text
        }
        messages.add(user)
        // Keep the draft text so we can restore it if the send fails; otherwise the
        // user's message is lost and they'd have to retype it.
        val savedDraft = draft
        draft = ""
        generating = true
        error = null
        emit()

        val body = Protocol.json.encodeToString(PromptBody.serializer(), PromptBody(listOf(TextInput(text = text))))
        sendMessageJob?.cancel()
        sendMessageJob = scope.launch {
            val result = http.postJson("$serverUrl/session/$session/prompt_async", body, authHeader())
            result.fold(
                onSuccess = { resp ->
                    if (resp.code in 200..299) {
                        messages.firstOrNull { it.id == user.id }?.status = MessageStatus.Sent
                    } else {
                        messages.firstOrNull { it.id == user.id }?.status = MessageStatus.Failed
                        generating = false
                        messages.forEach { it.streaming = false }
                        draft = savedDraft
                        error = "Server returned status ${resp.code}"
                    }
                },
                onFailure = { e ->
                    messages.firstOrNull { it.id == user.id }?.status = MessageStatus.Failed
                    generating = false
                    messages.forEach { it.streaming = false }
                    draft = savedDraft
                    error = "Request failed: ${e.message}"
                },
            )
            emit()
        }
    }

    private suspend fun probeHealth() {
        val hasCreds = hasCredentials()
        http.get("$serverUrl/global/health", authHeader()).fold(
            onSuccess = { resp ->
                when {
                    resp.code == 401 -> {
                        loading = false
                        // A prior connection may have succeeded (connected = true); a
                        // re-probe returning 401 (e.g. expired credentials) means we're
                        // no longer authenticated, so drop the connected flag.
                        connected = false
                        // Always reveal the auth fields so the user can see/edit
                        // their credentials — even when the ones supplied were wrong.
                        authRequired = true
                        if (hasCreds) error = "Invalid credentials"
                        emit()
                    }
                    resp.code in 200..299 -> {
                        connected = true
                        authRequired = false
                        error = null
                        screen = Screen.Sessions
                        loading = true
                        emit()
                        startSse()
                        // Route through loadSessionsJob so a prior in-flight loadSessions
                        // (e.g. from a rapid double-connect) is cancelled first.
                        loadSessionsJob?.cancel()
                        loadSessionsJob = scope.launch { loadSessions() }
                    }
                    else -> {
                        loading = false
                        connected = false
                        error = "Server returned status ${resp.code}"
                        emit()
                    }
                }
            },
            onFailure = { e ->
                loading = false
                connected = false
                error = "Connection failed: ${e.message}"
                emit()
            },
        )
    }

    private suspend fun loadSessions() {
        http.get("$serverUrl/session", authHeader()).fold(
            onSuccess = { resp ->
                if (resp.code in 200..299) {
                    sessions.clear()
                    sessions.addAll(Protocol.parseSessions(resp.body).map { SessionView(it.id, displayTitle(it.title)) })
                    // Impose a stable alphabetical order so the list doesn't shuffle with
                    // whatever order the server happens to return sessions in.
                    sessions.sortBy { it.title.lowercase() }
                } else {
                    error = "Server returned status ${resp.code}"
                }
            },
            onFailure = { e -> error = "Request failed: ${e.message}" },
        )
        loading = false
        emit()
    }

    private suspend fun createSession() {
        http.postJson("$serverUrl/session", "{}", authHeader()).fold(
            onSuccess = { resp ->
                val session = if (resp.code in 200..299) Protocol.parseSession(resp.body) else null
                if (session != null) {
                    if (sessions.none { it.id == session.id }) {
                        sessions.add(0, SessionView(session.id, displayTitle(session.title)))
                        // Restore the alphabetical order immediately (matching loadSessions /
                        // SessionUpserted) so the new session doesn't sit out-of-order at the
                        // top until the SSE SessionUpserted event arrives to re-sort.
                        sessions.sortBy { it.title.lowercase() }
                    }
                    emit()
                    // SelectSession takes over the loading state (loads messages).
                    dispatch(Event.SelectSession(session.id))
                    return
                }
                error = "Server returned status ${resp.code}"
            },
            onFailure = { e -> error = "Request failed: ${e.message}" },
        )
        loading = false
        emit()
    }

    private suspend fun loadMessages(sessionId: String) {
        http.get("$serverUrl/session/$sessionId/message", authHeader()).fold(
            onSuccess = { resp ->
                if (!isCurrent(sessionId)) { loading = false; return }  // user navigated away while loading
                loading = false
                if (resp.code in 200..299) {
                    messages.clear()
                    removedMessageIds.clear()
                    messages.addAll(Protocol.parseMessages(resp.body).map(::messageFromWire))
                    generating = messages.any { it.streaming }
                } else {
                    error = "Server returned status ${resp.code}"
                }
                emit()
            },
            onFailure = { e ->
                if (!isCurrent(sessionId)) { loading = false; return }
                loading = false
                generating = false
                error = "Request failed: ${e.message}"
                emit()
            },
        )
    }

    // ── SSE ─────────────────────────────────────────────────────────────────────
    private fun startSse() {
        closeSse()
        sseClosedIntentionally = false
        val client = SseClient(
            url = "$serverUrl/event",
            auth = authHeader(),
            onEvent = { json -> scope.launch { handleServerEvent(json) } },
            onStateChange = { open ->
                scope.launch {
                    sseConnected = open
                    if (open) reconnectAttempts = 0 else scheduleReconnect()
                    emit()
                }
            },
        )
        sse = client
        client.connect()
    }

    private fun closeSse() {
        sseClosedIntentionally = true
        reconnectJob?.cancel()
        reconnectJob = null
        sse?.close()
        sse = null
    }

    private fun scheduleReconnect() {
        if (sseClosedIntentionally || !connected) return
        // Exponential backoff: 2s, 4s, 8s, 16s, capped at 30s. Reset to 0 once the
        // stream reconnects (onStateChange(true)) so a healthy server starts fresh.
        val delayMs = (2000L * (1L shl reconnectAttempts)).coerceAtMost(30_000L)
        if (delayMs < 30_000L) reconnectAttempts += 1
        // Cancel any pending reconnect so rapid network flapping can't stack multiple
        // coroutines that each open a new SSE connection.
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!sseClosedIntentionally && connected && screen != Screen.Connect) startSse()
        }
    }

    private fun handleServerEvent(json: String) {
        val event = Protocol.parseEvent(json) ?: return
        // Only re-render if the event actually changed our state; applyEvent returns
        // false for events that don't apply (other session, unknown message, …).
        if (applyEvent(event)) emit()
    }

    /** Apply a server event to the model. Returns true iff any state actually changed. */
    private fun applyEvent(event: Protocol.Event): Boolean {
        when (event) {
            is Protocol.Event.MessageUpdated -> {
                val info = event.info
                if (info.role != "assistant" || !isCurrent(info.sessionId)) return false
                // A MessageRemoved may have arrived before this (late/out-of-order) update;
                // don't re-create a message the server has already deleted.
                if (info.id in removedMessageIds) return false
                val streaming = info.time.completed == null && info.error == null
                val existing = messages.firstOrNull { it.id == info.id }
                if (existing != null) {
                    existing.streaming = streaming
                    if (existing.time == 0L) existing.time = info.time.created
                } else {
                    messages.add(MsgState(info.id, "assistant", info.time.created).apply { this.streaming = streaming })
                }
                info.error?.let { error = it.message(); messages.forEach { m -> m.streaming = false }; generating = false }
                return true
            }

            is Protocol.Event.PartUpdated -> {
                val part = event.part
                val msg = messages.firstOrNull { it.id == part.messageId } ?: return false
                // Synthetic text parts are intentionally skipped; report no change so we
                // don't trigger a needless re-render/emit for them.
                return when (part) {
                    is Protocol.Part.Text -> if (part.synthetic) {
                        false
                    } else {
                        msg.textParts[part.id] = pickText(part.text, event.delta, msg.textParts[part.id])
                        true
                    }
                    is Protocol.Part.Reasoning -> {
                        msg.reasoningParts[part.id] = pickText(part.text, event.delta, msg.reasoningParts[part.id])
                        true
                    }
                    is Protocol.Part.Tool -> {
                        msg.toolParts[part.id] = ToolView(part.tool, part.status, part.title)
                        true
                    }
                }
            }

            is Protocol.Event.PartRemoved -> {
                val msg = messages.firstOrNull { it.id == event.messageId } ?: return false
                msg.remove(event.partId)
                return true
            }

            is Protocol.Event.MessageRemoved -> {
                // Remember the ID so a late MessageUpdated can't resurrect the message.
                removedMessageIds.add(event.messageId)
                return messages.removeAll { it.id == event.messageId }
            }

            is Protocol.Event.SessionIdle -> {
                if (!isCurrent(event.sessionId)) return false
                generating = false
                messages.forEach { it.streaming = false }
                return true
            }

            is Protocol.Event.SessionError -> {
                val scoped = event.sessionId == null || isCurrent(event.sessionId)
                if (!scoped) return false
                event.error?.let { error = it.message() }
                generating = false
                messages.forEach { it.streaming = false }
                return true
            }

            is Protocol.Event.SessionUpserted -> {
                val title = displayTitle(event.info.title)
                val existing = sessions.firstOrNull { it.id == event.info.id }
                if (existing != null) {
                    sessions[sessions.indexOf(existing)] = SessionView(event.info.id, title)
                } else {
                    sessions.add(0, SessionView(event.info.id, title))
                }
                // Keep the list alphabetically sorted (matching loadSessions) so an
                // upserted session doesn't jump to the top and break the ordering.
                sessions.sortBy { it.title.lowercase() }
                if (isCurrent(event.info.id)) currentSessionTitle = title
                return true
            }

            is Protocol.Event.SessionDeleted -> {
                val id = event.sessionId ?: return false
                val removed = sessions.removeAll { it.id == id }
                if (isCurrent(id)) {
                    currentSessionId = null
                    messages.clear()
                    screen = Screen.Sessions
                    return true
                }
                return removed
            }

            Protocol.Event.Other -> return false
        }
    }

    // ── View / helpers ──────────────────────────────────────────────────────────
    private fun snapshot() = UiState(
        screen = screen,
        serverUrl = serverUrl,
        username = username,
        password = password,
        authRequired = authRequired,
        connected = connected,
        loading = loading,
        error = error,
        sessions = sessions.toList(),
        currentSessionId = currentSessionId,
        currentSessionTitle = currentSessionTitle,
        messages = messages.map { it.toView() },
        draft = draft,
        generating = generating,
        sseConnected = sseConnected,
    )

    private fun emit() { _view.value = snapshot() }

    private fun isCurrent(sessionId: String?) = sessionId != null && currentSessionId == sessionId
    private fun hasCredentials() = username.isNotEmpty() || password.isNotEmpty()

    private fun authHeader(): String? {
        if (!hasCredentials()) return null
        val encoded = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        return "Basic $encoded"
    }
}

// ── Internal message state (parts tracked by id for streaming) ─────────────────
private class MsgState(val id: String, val role: String, var time: Long) {
    var status = MessageStatus.Sent
    var streaming = false
    val textParts = LinkedHashMap<String, String>()
    val reasoningParts = LinkedHashMap<String, String>()
    val toolParts = LinkedHashMap<String, ToolView>()

    fun remove(partId: String) {
        textParts.remove(partId)
        reasoningParts.remove(partId)
        toolParts.remove(partId)
    }

    fun toView(): MessageView {
        val reasoning = reasoningParts.values.joinToString("")
        return MessageView(
            id = id,
            role = role,
            text = textParts.values.joinToString(""),
            reasoning = reasoning.ifEmpty { null },
            tools = toolParts.values.toList(),
            time = time,
            status = status,
            streaming = streaming,
        )
    }
}

private fun messageFromWire(env: Protocol.WireMessageEnvelope): MsgState {
    val info = env.info
    val msg = MsgState(info.id, info.role, info.time.created)
    msg.streaming = info.role == "assistant" && info.time.completed == null && info.error == null
    for (raw in env.parts) {
        val obj = (raw as? kotlinx.serialization.json.JsonObject) ?: continue
        when (val part = Protocol.parsePart(obj)) {
            is Protocol.Part.Text -> if (!part.synthetic) msg.textParts[part.id] = part.text
            is Protocol.Part.Reasoning -> msg.reasoningParts[part.id] = part.text
            is Protocol.Part.Tool -> msg.toolParts[part.id] = ToolView(part.tool, part.status, part.title)
            null -> Unit
        }
    }
    return msg
}

private fun pickText(text: String, delta: String?, prior: String?): String =
    if (text.isNotEmpty()) text else (prior.orEmpty() + (delta ?: ""))

private fun normalizeUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    // Default to http:// when the user didn't type a scheme (e.g. "192.168.1.10:4096").
    // Without a scheme OkHttp's URL parser throws and the connect attempt crashes.
    return when {
        trimmed.isEmpty() -> ""
        "://" in trimmed -> trimmed
        else -> "http://$trimmed"
    }
}

private fun displayTitle(title: String): String = title.trim().ifEmpty { "Untitled" }

// ── Prompt request body (POST /session/{id}/prompt_async) ──────────────────────
@kotlinx.serialization.Serializable
private data class PromptBody(val parts: List<TextInput>)

@kotlinx.serialization.Serializable
private data class TextInput(val type: String = "text", val text: String)
