package soy.iko.opencode.core

import com.novi.serde.Bytes

/**
 * Pure-Kotlin fallback implementation of the Crux core.
 *
 * This mirrors the Rust [app.rs] logic so the app can run during development
 * without the compiled native library. When [libopencode.so] is available,
 * [NativeCoreFfi] is used instead and this class is never instantiated.
 *
 * Basic-auth detection: the Connect event probes `/global/health`. A 401
 * response sets [PureModel.authRequired] and surfaces credential fields in
 * the view. When credentials are present, subsequent requests carry the
 * `Authorization: Basic` header.
 */
internal class PureCoreFfi : CoreFfi {
    private val model = PureModel()

    override fun update(data: ByteArray): ByteArray {
        val event = Event.bincodeDeserialize(data)
        val effects = model.handleEvent(event)
        return effects.bincodeSerialize()
    }

    override fun resolve(id: UInt, data: ByteArray): ByteArray {
        val result = HttpResult.bincodeDeserialize(data)
        val effects = model.handleResult(id, result)
        return effects.bincodeSerialize()
    }

    override fun view(): ByteArray {
        return model.currentView().bincodeSerialize()
    }
}

internal data class PureModel(
    var serverUrl: String = "http://localhost:4096",
    var username: String = "",
    var password: String = "",
    var authRequired: Boolean = false,
    var authed: Boolean = false,
    var connected: Boolean = false,
    var loading: Boolean = false,
    var error: String? = null,
    var sessions: MutableList<PureSession> = mutableListOf(),
    var currentSessionId: String? = null,
    var messages: MutableList<PureMessage> = mutableListOf(),
    var draftMessage: String = "",
    var crashLogs: MutableList<String> = mutableListOf(),
    var pendingRequests: MutableMap<UInt, PendingRequest> = mutableMapOf(),
    var nextId: UInt = 0u,
) {
    fun handleEvent(event: Event): Requests {
        val requests = mutableListOf<Request>()
        when (event) {
            is Event.Start -> {
                serverUrl = "http://localhost:4096"
            }
            is Event.ServerUrlChanged -> {
                serverUrl = event.value
            }
            is Event.UsernameChanged -> {
                username = event.value
            }
            is Event.PasswordChanged -> {
                password = event.value
            }
            is Event.Connect -> {
                loading = true
                error = null
                val id = nextId++
                pendingRequests[id] = PendingRequest.HealthProbe
                requests.add(Request(id, Effect.Http(makeGetRequest("${serverUrl}/global/health"))))
            }
            is Event.CancelAuth -> {
                authRequired = false
                username = ""
                password = ""
                loading = false
            }
            is Event.LoadSessions -> {
                loading = true
                val id = nextId++
                pendingRequests[id] = PendingRequest.LoadSessions
                requests.add(Request(id, Effect.Http(makeGetRequest("${serverUrl}/session"))))
            }
            is Event.SelectSession -> {
                currentSessionId = event.value
                messages.clear()
                val id = nextId++
                pendingRequests[id] = PendingRequest.LoadMessages(event.value)
                requests.add(
                    Request(id, Effect.Http(makeGetRequest("${serverUrl}/session/${event.value}/message"))),
                )
            }
            is Event.CreateSession -> {
                loading = true
                val id = nextId++
                pendingRequests[id] = PendingRequest.CreateSession
                requests.add(
                    Request(id, Effect.Http(makePostRequest("${serverUrl}/session", ByteArray(0)))),
                )
            }
            is Event.LoadMessages -> {
                loading = true
                val id = nextId++
                pendingRequests[id] = PendingRequest.LoadMessages(event.value)
                requests.add(
                    Request(id, Effect.Http(makeGetRequest("${serverUrl}/session/${event.value}/message"))),
                )
            }
            is Event.SendMessage -> {
                val sessionId = currentSessionId ?: return Requests(requests)
                draftMessage = ""
                val body = """{"sessionID":"$sessionId","parts":[{"type":"text","text":"${event.value}"}]}"""
                    .toByteArray()
                val id = nextId++
                pendingRequests[id] = PendingRequest.SendMessage(sessionId)
                requests.add(
                    Request(
                        id,
                        Effect.Http(
                            makePostRequest("${serverUrl}/session/$sessionId/prompt_async", body)
                                .copy(headers = listOf(HttpHeader("Content-Type", "application/json"))),
                        ),
                    ),
                )
            }
            is Event.EventReceived -> {
                val sid = currentSessionId
                if (sid != null) {
                    val id = nextId++
                    pendingRequests[id] = PendingRequest.LoadMessages(sid)
                    requests.add(
                        Request(id, Effect.Http(makeGetRequest("${serverUrl}/session/$sid/message"))),
                    )
                }
            }
            is Event.NavigateToChat -> {
                currentSessionId = event.value
                messages.clear()
            }
            is Event.NavigateToSessions -> {
                currentSessionId = null
                messages.clear()
            }
            is Event.DismissError -> {
                error = null
            }
            else -> {}
        }
        return Requests(requests)
    }

    fun handleResult(id: UInt, result: HttpResult): Requests {
        val requests = mutableListOf<Request>()
        val pending = pendingRequests.remove(id) ?: return Requests(requests)

        when (pending) {
            is PendingRequest.HealthProbe -> {
                when (result) {
                    is HttpResult.Ok -> {
                        val status = result.value.status.toInt()
                        if (status == 401) {
                            loading = false
                            val hasCreds = username.isNotEmpty() || password.isNotEmpty()
                            if (hasCreds) {
                                error = "Invalid credentials"
                            } else {
                                authRequired = true
                            }
                        } else if (status in 200..299) {
                            val hasCreds = username.isNotEmpty() || password.isNotEmpty()
                            if (hasCreds) authed = true
                            loading = true
                            val newId = nextId++
                            pendingRequests[newId] = PendingRequest.LoadSessions
                            requests.add(
                                Request(newId, Effect.Http(makeGetRequest("${serverUrl}/session"))),
                            )
                        } else {
                            loading = false
                            error = "Server returned status $status"
                        }
                    }
                    is HttpResult.Err -> {
                        loading = false
                        error = "Connection failed: ${result.value}"
                    }
                }
            }
            is PendingRequest.LoadSessions -> {
                loading = false
                when (result) {
                    is HttpResult.Ok -> {
                        val body = result.value.body.content.decodeToString()
                        sessions = parseSessions(body).toMutableList()
                        connected = true
                    }
                    is HttpResult.Err -> {
                        error = "Failed to load sessions: ${result.value}"
                    }
                }
            }
            is PendingRequest.CreateSession -> {
                loading = false
                when (result) {
                    is HttpResult.Ok -> {
                        val body = result.value.body.content.decodeToString()
                        val sid = extractJsonString(body, "id") ?: ""
                        if (sid.isNotEmpty()) {
                            currentSessionId = sid
                            messages.clear()
                            val newId = nextId++
                            pendingRequests[newId] = PendingRequest.LoadMessages(sid)
                            requests.add(
                                Request(newId, Effect.Http(makeGetRequest("${serverUrl}/session/$sid/message"))),
                            )
                        }
                    }
                    is HttpResult.Err -> {
                        error = "Failed to create session: ${result.value}"
                    }
                }
            }
            is PendingRequest.LoadMessages -> {
                loading = false
                when (result) {
                    is HttpResult.Ok -> {
                        val body = result.value.body.content.decodeToString()
                        messages = parseMessages(body).toMutableList()
                    }
                    is HttpResult.Err -> {
                        error = "Failed to load messages: ${result.value}"
                    }
                }
            }
            is PendingRequest.SendMessage -> {
                when (result) {
                    is HttpResult.Ok -> {
                        val sid = pending.sessionId
                        val newId = nextId++
                        pendingRequests[newId] = PendingRequest.LoadMessages(sid)
                        requests.add(
                            Request(newId, Effect.Http(makeGetRequest("${serverUrl}/session/$sid/message"))),
                        )
                    }
                    is HttpResult.Err -> {
                        error = "Failed to send message: ${result.value}"
                    }
                }
            }
        }
        return Requests(requests)
    }

    fun currentView(): ViewModel {
        val screen = when {
            currentSessionId != null -> Screen.CHAT
            connected -> Screen.SESSIONS
            else -> Screen.CONNECT
        }
        return ViewModel(
            screen = screen,
            serverUrl = serverUrl,
            username = username,
            password = password,
            authRequired = authRequired,
            connected = connected,
            loading = loading,
            error = error,
            sessions = sessions.map { SessionView(it.id, it.title) },
            currentSessionId = currentSessionId,
            messages = messages.map { MessageView(it.id, it.role, it.text, it.time) },
            draftMessage = draftMessage,
            crashLogCount = crashLogs.size.toUInt(),
            latestCrashLog = crashLogs.lastOrNull(),
        )
    }

    private fun makeGetRequest(url: String): HttpRequest {
        val headers = if (username.isNotEmpty() || password.isNotEmpty()) {
            val creds = "$username:$password"
            val encoded = java.util.Base64.getEncoder().encodeToString(creds.toByteArray())
            listOf(HttpHeader("Authorization", "Basic $encoded"))
        } else {
            emptyList()
        }
        return HttpRequest(method = "GET", url = url, headers = headers, body = Bytes(ByteArray(0)))
    }

    private fun makePostRequest(url: String, body: ByteArray): HttpRequest {
        val headers = if (username.isNotEmpty() || password.isNotEmpty()) {
            val creds = "$username:$password"
            val encoded = java.util.Base64.getEncoder().encodeToString(creds.toByteArray())
            listOf(HttpHeader("Authorization", "Basic $encoded"))
        } else {
            emptyList()
        }
        return HttpRequest(method = "POST", url = url, headers = headers, body = Bytes(body))
    }
}

internal sealed class PendingRequest {
    data object HealthProbe : PendingRequest()
    data object LoadSessions : PendingRequest()
    data object CreateSession : PendingRequest()
    data class LoadMessages(val sessionId: String) : PendingRequest()
    data class SendMessage(val sessionId: String) : PendingRequest()
}

internal data class PureSession(val id: String, val title: String)

internal data class PureMessage(val id: String, val role: String, val text: String, val time: ULong)

internal fun parseSessions(json: String): List<PureSession> {
    val sessions = mutableListOf<PureSession>()
    var depth = 0
    var start = 0
    for (i in json.indices) {
        when (json[i]) {
            '{' -> {
                if (depth == 0) start = i
                depth++
            }
            '}' -> {
                depth--
                if (depth == 0) {
                    val obj = json.substring(start, i + 1)
                    val id = extractJsonString(obj, "id") ?: ""
                    val title = extractJsonString(obj, "title") ?: ""
                    if (id.isNotEmpty()) {
                        sessions.add(PureSession(id, if (title.isNotEmpty()) title else "Untitled"))
                    }
                }
            }
        }
    }
    return sessions
}

internal fun parseMessages(json: String): List<PureMessage> {
    val messages = mutableListOf<PureMessage>()
    var depth = 0
    var start = 0
    for (i in json.indices) {
        when (json[i]) {
            '{' -> {
                if (depth == 0) start = i
                depth++
            }
            '}' -> {
                depth--
                if (depth == 0) {
                    val obj = json.substring(start, i + 1)
                    val id = extractJsonString(obj, "id") ?: ""
                    val role = extractJsonString(obj, "role") ?: ""
                    val time = extractJsonNumber(obj, "created") ?: 0uL
                    if (id.isNotEmpty() && role.isNotEmpty()) {
                        messages.add(PureMessage(id, role, "", time))
                    }
                }
            }
        }
    }
    return messages
}

internal fun extractJsonString(json: String, key: String): String? {
    val pattern = "\"$key\""
    val idx = json.indexOf(pattern) ?: return null
    val rest = json.substring(idx + pattern.length)
    val colon = rest.indexOf(':') ?: return null
    val rest2 = rest.substring(colon + 1)
    val quote = rest2.indexOf('"') ?: return null
    val rest3 = rest2.substring(quote + 1)
    val end = rest3.indexOf('"') ?: return null
    return rest3.substring(0, end)
}

internal fun extractJsonNumber(json: String, key: String): ULong? {
    val pattern = "\"$key\""
    val idx = json.indexOf(pattern) ?: return null
    val rest = json.substring(idx + pattern.length)
    val colon = rest.indexOf(':') ?: return null
    val rest2 = rest.substring(colon + 1).trimStart()
    val end = rest2.indexOfFirst { !it.isDigit() }.let { if (it == -1) rest2.length else it }
    return rest2.substring(0, end).toULongOrNull()
}
