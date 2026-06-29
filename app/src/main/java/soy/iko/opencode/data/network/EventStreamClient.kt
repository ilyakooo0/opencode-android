package soy.iko.opencode.data.network

import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.data.model.ServerConnected
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive

/**
 * Owns the single long-lived `GET /event` SSE subscription and exposes it as a hot
 * [SharedFlow] of [BusEvent]. The connection is opened when the first collector
 * subscribes (WhileSubscribed) and reconnects with backoff on drop.
 *
 * Ordering contract: callers that send a prompt must already be collecting [events]
 * (directly or via the repository) so early `message.part.updated` deltas aren't missed.
 */
class EventStreamClient(
    private val client: HttpClient,
    scope: CoroutineScope,
) {
    enum class ConnectionState { Disconnected, Connecting, Connected }

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    val events: SharedFlow<BusEvent> = stream()
        .shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 0,
        )

    private fun stream(): Flow<BusEvent> = channelFlow {
        var backoffMs = INITIAL_BACKOFF_MS
        while (isActive) {
            _state.value = ConnectionState.Connecting
            try {
                client.sse("event") {
                    backoffMs = INITIAL_BACKOFF_MS
                    incoming.collect { sse ->
                        val data = sse.data ?: return@collect
                        val event = runCatching { OpencodeJson.decodeFromString(BusEvent.serializer(), data) }
                            .getOrNull() ?: return@collect
                        if (event is ServerConnected) {
                            _state.value = ConnectionState.Connected
                        }
                        send(event)
                    }
                }
            } catch (t: Throwable) {
                if (!isActive) throw t
                // fall through to reconnect
            }
            _state.value = ConnectionState.Disconnected
            if (!isActive) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 10_000L
    }
}
