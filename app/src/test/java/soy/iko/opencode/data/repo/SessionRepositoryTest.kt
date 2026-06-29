package soy.iko.opencode.data.repo

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import soy.iko.opencode.data.network.EventStreamClient
import soy.iko.opencode.data.network.OpencodeApiClient
import soy.iko.opencode.data.network.OpencodeJson

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {

    private val session = "s1"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    /**
     * Verify that observeMessages seeds from the REST endpoint. The SSE stream uses
     * a MockEngine that always errors (MockEngine can't produce a streaming SSE body),
     * so only the REST seed path is exercised here. The SSE→REST merge logic is
     * unit-tested in [MessageStoreTest].
     */
    @Test
    fun seedsFromRestWithSseErrors() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // REST: listMessages returns one user message.
        val restEngine = MockEngine {
            respond(
                """[{"info":{"role":"user","id":"u1","sessionID":"$session"},"parts":[]}]""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val restClient = HttpClient(restEngine) {
            install(ContentNegotiation) { json(OpencodeJson) }
        }
        val api = OpencodeApiClient(restClient)

        // SSE: always errors (we only test the REST seed path here).
        val sseEngine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val sseClient = HttpClient(sseEngine) { install(SSE) }
        val eventStream = EventStreamClient(sseClient, scope)

        val repo = SessionRepository(api, eventStream)

        // The first non-empty emission should be the REST seed.
        val result = repo.observeMessages(session).first { it.isNotEmpty() }

        assertEquals(1, result.size)
        assertEquals("u1", result[0].info.id)

        scope.cancel()
        restClient.close()
        sseClient.close()
    }
}
