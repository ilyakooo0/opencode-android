package soy.iko.opencode.data.network

import soy.iko.opencode.data.model.BusEvent
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.io.IOException

/**
 * Owns the single long-lived `GET /event` SSE subscription and exposes it as a hot
 * [SharedFlow] of [BusEvent]. The connection is opened when the first collector
 * subscribes (WhileSubscribed) and reconnects with backoff on drop.
 *
 * Ordering contract: callers that send a prompt must already be collecting [events]
 * (directly or via the repository) so early `message.part.updated` deltas aren't missed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventStreamClient(
    private val client: HttpClient,
    scope: CoroutineScope,
) {
    enum class ConnectionState { Disconnected, Connecting, Connected }

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    val events: SharedFlow<BusEvent> = stream()
        // Buffer so a slow subscriber (e.g. a reducer under lock) can't suspend send()
        // and stall the SSE read loop. Drop oldest on overflow — the next event carries
        // the freshest state, so a dropped interim delta is harmless.
        .buffer(NetworkConfig.sseEventBufferCapacity, BufferOverflow.DROP_OLDEST)
        .shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 0,
        )

    /** Conflated channel used to cut the reconnect backoff short when network returns. */
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)

    /** Request an immediate reconnect, skipping any in-progress backoff. */
    fun triggerReconnect() { reconnectSignal.trySend(Unit) }

    private fun stream(): Flow<BusEvent> = channelFlow {
        val scope = this
        var backoffMs = NetworkConfig.sseInitialBackoffMs
        while (isActive) {
            _state.value = ConnectionState.Connecting
            try {
                client.sse("event") {
                    backoffMs = NetworkConfig.sseInitialBackoffMs
                    // `incoming` is a cold Flow; bridge it to a ReceiveChannel so each
                    // element can be raced against an idle timeout via select.
                    val events = incoming.produceIn(scope)
                    try {
                        while (isActive) {
                            // The OkHttp engine has readTimeout=0 (the SSE stream must
                            // never time out on read), so a socket the peer never closed
                            // would otherwise hang until OS TCP keepalive kicks in —
                            // minutes to hours on stock Linux. If nothing arrives within
                            // the idle timeout, treat the connection as half-open and
                            // drop it so the outer loop reconnects.
                            val result: ChannelResult<ServerSentEvent>? = select {
                                events.onReceiveCatching { it }
                                onTimeout(NetworkConfig.sseIdleTimeoutMs) { null }
                            }
                            // Timeout: drop and reconnect.
                            if (result == null) throw IOException("SSE idle timeout, reconnecting")
                            // Stream closed cleanly: exit to reconnect without a warning log.
                            val sse = result.getOrNull() ?: break
                            val data = sse.data ?: continue
                            val event = runCatching {
                                OpencodeJson.decodeFromString(BusEvent.serializer(), data)
                            }.getOrNull() ?: continue
                            // Any successfully decoded event means the stream is live;
                            // don't wait for a ServerConnected event to show "Connected".
                            if (_state.value != ConnectionState.Connected) {
                                _state.value = ConnectionState.Connected
                            }
                            send(event)
                        }
                    } finally {
                        events.cancel()
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w("EventStream", "SSE stream error, will retry", t)
            }
            _state.value = ConnectionState.Disconnected
            if (!isActive) break
            // Wait for the backoff, but allow a reconnect signal to cut it short.
            var signaled = false
            select {
                reconnectSignal.onReceive { signaled = true }
                onTimeout(backoffMs) { /* normal backoff elapsed */ }
            }
            backoffMs = if (signaled) NetworkConfig.sseInitialBackoffMs else (backoffMs * 2).coerceAtMost(NetworkConfig.sseMaxBackoffMs)
        }
    }
}
