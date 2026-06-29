package soy.iko.opencode.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventStreamClientTest {

    /**
     * The SSE plugin requires a streaming response body which MockEngine can't provide,
     * so full SSE integration isn't testable in pure JVM tests. The reconnect/backoff
     * logic is covered by [WithRetryTest] (same exponential-backoff formula) and the
     * event decoding is covered by [OpencodeJsonTest]. Here we verify only the initial
     * state — the connection-state machine starts in Disconnected.
     */
    @Test
    fun stateIsDisconnectedBeforeSubscribing() {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val client = HttpClient(engine) { install(SSE) }
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val sse = EventStreamClient(client, scope)

        assertEquals(EventStreamClient.ConnectionState.Disconnected, sse.state.value)

        scope.cancel()
        client.close()
    }
}
