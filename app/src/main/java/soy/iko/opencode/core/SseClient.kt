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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import soy.iko.opencode.CrashLogger

/**
 * Subscribes to the opencode server's SSE event stream.
 *
 * Events are emitted as raw strings on [events]. The Core converts them into
 * [Event.EventReceived] which triggers a message reload.
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

    private var currentJob: Job? = null

    fun connect(url: String) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                httpClient.prepareGet(url).execute { response ->
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
                }
            } catch (e: Throwable) {
                CrashLogger.report(e, "SseClient")
            }
        }
    }

    fun disconnect() {
        currentJob?.cancel()
        currentJob = null
    }
}
