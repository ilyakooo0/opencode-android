package soy.iko.opencode.data.network

import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.util.safeExceptionSummary
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext
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
import kotlin.random.Random

/**
 * Owns the single long-lived `GET /event` SSE subscription and exposes it as a hot
 * [SharedFlow] of [BusEvent]. The connection is opened when the first collector
 * subscribes (WhileSubscribed) and reconnects with backoff on drop.
 *
 * Ordering contract: callers that send a prompt must already be collecting [events]
 * (directly or via the repository) so early `message.part.updated` deltas aren't missed.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
open class EventStreamClient(
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = NetworkConfig.sseIdleTimeoutMs,
    private val initialBackoffMs: Long = NetworkConfig.sseInitialBackoffMs,
    private val maxBackoffMs: Long = NetworkConfig.sseMaxBackoffMs,
    private val bufferCapacity: Int = NetworkConfig.sseEventBufferCapacity,
) {
    protected constructor() : this(
        HttpClient(io.ktor.client.engine.okhttp.OkHttp) {},
        CoroutineScope(EmptyCoroutineContext),
    )
    enum class ConnectionState { Disconnected, Connecting, Connected, Failed }

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    open val state: StateFlow<ConnectionState> = _state.asStateFlow()

    open val events: SharedFlow<BusEvent> by lazy {
        stream()
            .buffer(bufferCapacity, BufferOverflow.DROP_OLDEST)
            .shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = NetworkConfig.stateFlowSubscriptionTimeoutMs),
                replay = 0,
            )
    }

    /** Conflated channel used to cut the reconnect backoff short when network returns. */
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)

    /** Returns true if the exception is an SSE auth failure (401/403) that should stop retrying. */
    private fun isSseAuthFailure(e: Throwable): Boolean =
        e is io.ktor.client.plugins.ClientRequestException &&
            (e.response.status.value == 401 || e.response.status.value == 403)

    /** Request an immediate reconnect, skipping any in-progress backoff. */
    open fun triggerReconnect() { reconnectSignal.trySend(Unit) }

    /**
     * Read SSE events from [events], racing each read against an idle-timeout watchdog.
     * Throws [IOException] on idle timeout so the outer reconnect loop fires; breaks on
     * clean stream close or scope cancellation.
     */
    private suspend fun readSseEvents(
        scope: CoroutineScope,
        events: kotlinx.coroutines.channels.ReceiveChannel<ServerSentEvent>,
        send: suspend (BusEvent) -> Unit,
    ) {
        while (scope.isActive) {
            // The OkHttp engine has readTimeout=0 (the SSE stream must never time out on
            // read), so a socket the peer never closed would otherwise hang until OS TCP
            // keepalive kicks in — minutes to hours on stock Linux. If nothing arrives
            // within the idle timeout, treat the connection as half-open and drop it.
            val result: ChannelResult<ServerSentEvent>? = select {
                events.onReceiveCatching { it }
                onTimeout(idleTimeoutMs) { null }
            }
            if (result == null) throw IOException("SSE idle timeout, reconnecting")
            val sse = result.getOrNull() ?: break
            val data = sse.data
            if (data == null) {
                // A comment or keep-alive event (no data field) resets the idle watchdog
                // — servers send `: ping` comments to keep the connection alive through
                // proxies. Without this, a server that only sends comments would still
                // hit the idle timeout and reconnect unnecessarily.
                continue
            }
            val event = try {
                OpencodeJson.decodeFromString(BusEvent.serializer(), data)
            } catch (e: kotlinx.serialization.SerializationException) {
                Log.d("EventStream", "Skipping unparseable SSE event", e)
                continue
            }
            if (_state.value != ConnectionState.Connected) {
                _state.value = ConnectionState.Connected
            }
            send(event)
        }
    }

    private fun stream(): Flow<BusEvent> = channelFlow {
        val scope = this
        // Drain any stale reconnect signal from a previous collection cycle so it
        // doesn't spuriously reset the backoff on startup (WhileSubscribed may have
        // stopped the upstream while triggerReconnect() queued a signal).
        while (reconnectSignal.tryReceive().isSuccess) { /* drain */ }
        var backoffMs = initialBackoffMs
        while (isActive) {
            _state.value = ConnectionState.Connecting
            try {
                // The SSE stream is long-lived: disable the request-level and socket
                // timeouts (the client default is 60s for REST) in the request config
                // so the connection isn't killed mid-stream. The idle-timeout watchdog
                // below handles half-open connections instead.
                client.sse(
                    "event",
                    request = {
                        timeout {
                            requestTimeoutMillis = Long.MAX_VALUE
                            // Disable the socket (read) timeout entirely for the SSE stream.
                            // A finite value would kill the connection whenever the server
                            // is quiet for longer than that (idle sessions, slow agent runs),
                            // causing a reconnect storm. Half-open detection is handled by
                            // the idle-timeout watchdog below plus OkHttp's pingInterval on
                            // HTTP/2 connections.
                            socketTimeoutMillis = Long.MAX_VALUE
                        }
                    },
                ) {
                    // The SSE block is open — the connection is established. Mark as
                    // Connected immediately so downstream re-seed logic fires even when
                    // the server only sends keep-alive comments (no data events).
                    if (_state.value != ConnectionState.Connected) {
                        _state.value = ConnectionState.Connected
                    }
                    // Clear any stale reconnect signal from a triggerReconnect() call
                    // made while the previous stream was healthy, so it doesn't suppress
                    // the next legitimate backoff.
                    while (reconnectSignal.tryReceive().isSuccess) { /* drain */ }
                    // `incoming` is a cold Flow; bridge it to a ReceiveChannel so each
                    // element can be raced against an idle timeout via select.
                    val events = incoming.produceIn(scope)
                    // Reset the backoff only once real data actually arrives, not on
                    // block entry: a server that accepts the connection then immediately
                    // drops it would otherwise loop forever at the initial backoff and
                    // never escalate the exponential ramp.
                    var receivedAny = false
                    try {
                        readSseEvents(scope, events) {
                            if (!receivedAny) {
                                receivedAny = true
                                backoffMs = initialBackoffMs
                            }
                            send(it)
                        }
                    } finally {
                        events.cancel()
                    }
                }
            } catch (c: CancellationException) {
                _state.value = ConnectionState.Disconnected
                throw c
            } catch (e: Exception) {
                // If the scope was cancelled (e.g. connection close), the closed-client
                // exception is expected — don't log a spurious "stream error" warning.
                if (isSseAuthFailure(e)) {
                    // Credentials are wrong — retrying won't help. Log a scrubbed
                    // summary (class + status) instead of the full exception, whose
                    // message carries the request URL and may include auth or paths.
                    Log.w("EventStream", "SSE auth failed (401/403), awaiting explicit reconnect: ${safeExceptionSummary(e)}")
                    _state.value = ConnectionState.Failed
                    if (!isActive) break
                    // Don't complete the flow: a subscriber may fix the profile and call
                    // triggerReconnect(). Suspend until an explicit reconnect signal
                    // arrives (no backoff delay), then re-attempt the connection.
                    reconnectSignal.receive()
                    continue
                } else if (isActive) {
                    Log.w("EventStream", "SSE stream error, will retry: ${safeExceptionSummary(e)}")
                }
            }
            _state.value = ConnectionState.Disconnected
            if (!isActive) break
            // Wait for the backoff, but allow a reconnect signal to cut it short.
            var signaled = false
            val jitter = ((backoffMs * NetworkConfig.retryJitterFactor) * (Random.nextDouble() * 2 - 1)).toLong()
            select {
                reconnectSignal.onReceive { signaled = true }
                onTimeout((backoffMs + jitter).coerceAtLeast(0)) { /* normal backoff elapsed */ }
            }
            backoffMs = if (signaled) initialBackoffMs else (backoffMs * 2).coerceAtMost(maxBackoffMs)
        }
    }
}
