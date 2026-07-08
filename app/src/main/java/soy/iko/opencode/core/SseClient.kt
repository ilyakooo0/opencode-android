package soy.iko.opencode.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Long-lived subscription to the opencode `/event` SSE stream.
 *
 * Crux only models request/response HTTP, so the streaming connection lives here
 * in the shell: each decoded event's `data` payload (a JSON object whose own
 * `type` field is the discriminant) is handed to [onEvent], which the core then
 * parses via [Protocol.parseEvent].
 */
class SseClient(
    private val url: String,
    private val auth: String?,
    private val onEvent: (String) -> Unit,
    private val onStateChange: (Boolean) -> Unit,
) {
    // A dedicated client with no read timeout — the stream is meant to stay open.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var source: EventSource? = null

    fun connect() {
        val builder = Request.Builder().url(url).header("Accept", "text/event-stream")
        if (auth != null) builder.header("Authorization", auth)
        source = EventSources.createFactory(client).newEventSource(builder.build(), listener)
    }

    fun close() {
        source?.cancel()
        source = null
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            onStateChange(true)
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (data.isNotBlank()) onEvent(data)
        }

        override fun onClosed(eventSource: EventSource) {
            onStateChange(false)
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            onStateChange(false)
        }
    }
}
