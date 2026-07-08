package soy.iko.opencode.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import soy.iko.opencode.CrashLogger

/**
 * Subscribes to the opencode server's SSE event stream (`GET /event`).
 *
 * Events are emitted as raw strings on [events]. The Core converts them into
 * [Event.EventReceived] which triggers a message reload.
 *
 * The live connection status is exposed via [state] so the UI can surface a
 * "live stream disconnected" banner to the user instead of silently dropping
 * updates. Transport errors are still reported to [CrashLogger] for
 * diagnostics, but they are no longer hidden from the user.
 */
class SseClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _state = MutableStateFlow<SseState>(SseState.Disconnected)
    val state: StateFlow<SseState> = _state.asStateFlow()

    private var currentJob: Job? = null
    @Volatile private var currentUrl: String? = null

    fun connect(url: String) {
        currentUrl = url
        currentJob?.cancel()
        _state.value = SseState.Connecting
        currentJob = scope.launch {
            try {
                httpClient.prepareGet(url).execute { response ->
                    _state.value = SseState.Connected
                    val channel = response.bodyAsChannel()
                    val dataBuilder = StringBuilder()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        if (line.startsWith("data: ")) {
                            dataBuilder.append(line.substring(6))
                        } else if (line.isEmpty() && dataBuilder.isNotEmpty()) {
                            _events.emit(dataBuilder.toString())
                            dataBuilder.clear()
                        }
                    }
                    // Stream ended normally — flag as disconnected so the UI
                    // can offer a reconnect.
                    _state.value = SseState.Disconnected
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = SseState.Disconnected
                throw e
            } catch (e: Throwable) {
                CrashLogger.report(e, "SseClient")
                _state.value = SseState.Error(e.message ?: "Connection lost")
            }
        }
    }

    /** Re-establish the previous connection, if any. */
    fun reconnect() {
        currentUrl?.let { connect(it) }
    }

    fun disconnect() {
        currentJob?.cancel()
        currentJob = null
        currentUrl = null
        _state.value = SseState.Disconnected
    }
}

/** Live status of the SSE event stream. */
sealed interface SseState {
    /** Not connected (initial state, or after a clean close). */
    data object Disconnected : SseState
    /** Connection attempt in progress. */
    data object Connecting : SseState
    /** Stream is open and events are being received. */
    data object Connected : SseState
    /** The stream dropped unexpectedly. [message] describes the failure. */
    data class Error(val message: String) : SseState
}
