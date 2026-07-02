package soy.iko.opencode.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.iko.opencode.data.model.PermissionResponse
import java.io.IOException

class OpencodeApiClientTest {

    private fun makeClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(OpencodeJson) }
        }

    @Test
    fun respondPermissionRetriesOnTransientFailure() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls < 3) throw IOException("transient")
            respond("", HttpStatusCode.OK, headersOf("Content-Length", "0"))
        }
        val api = OpencodeApiClient(makeClient(engine))
        api.respondPermission("s1", "p1", PermissionResponse.ALWAYS)
        assertEquals("transient failures should be retried", 3, calls)
    }

    @Test
    fun respondPermissionDoesNotRetryClientError() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respondError(HttpStatusCode.BadRequest)
        }
        val api = OpencodeApiClient(makeClient(engine))
        val error = runCatching {
            api.respondPermission("s1", "p1", PermissionResponse.REJECT)
        }.exceptionOrNull()!!
        assertEquals("4xx must not be retried", 1, calls)
        assertTrue("expected ClientRequestException, got $error", error is ClientRequestException)
    }

    @Test
    fun respondPermissionSucceedsOnFirstTry() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("", HttpStatusCode.OK, headersOf("Content-Length", "0"))
        }
        val api = OpencodeApiClient(makeClient(engine))
        api.respondPermission("s1", "p1", PermissionResponse.ONCE)
        assertEquals(1, calls)
    }

    @Test
    fun sendPromptReusesSuppliedIdempotencyKeyForEveryRetry() = runTest {
        // Regression: the offline outbox re-sends a queued message across separate flushes
        // with a stable key (the outbox id) so a POST that reached the server but whose
        // response was lost is deduplicated instead of starting a duplicate run. Every retry
        // attempt must carry that exact key.
        val keys = mutableListOf<String?>()
        val engine = MockEngine { request ->
            keys += request.headers["Idempotency-Key"]
            throw IOException("transient") // force retries; the key must not change between them
        }
        val api = OpencodeApiClient(makeClient(engine))
        runCatching { api.sendPrompt("s1", "hello", idempotencyKey = "outbox-123") }
        assertTrue("expected more than one attempt, got ${keys.size}", keys.size > 1)
        assertTrue("every attempt must reuse the supplied key, saw $keys", keys.all { it == "outbox-123" })
    }

    @Test
    fun sendPromptDefaultKeyIsStableAcrossRetries() = runTest {
        // Even without a caller-supplied key, all retries of a single call share one generated
        // key (so an in-call retry after a lost response is deduplicated server-side).
        val keys = mutableListOf<String?>()
        val engine = MockEngine { request ->
            keys += request.headers["Idempotency-Key"]
            throw IOException("transient")
        }
        val api = OpencodeApiClient(makeClient(engine))
        runCatching { api.sendPrompt("s1", "hello") }
        assertTrue("expected more than one attempt", keys.size > 1)
        assertTrue("generated key must be non-null", keys.all { !it.isNullOrBlank() })
        assertEquals("all attempts must share one key", 1, keys.toSet().size)
    }

    @Test
    fun sendPromptDoesNotRetryMalformedResponseBody() = runTest {
        // A body that can't be deserialized is deterministic — retrying re-parses the same
        // bytes and fails identically, so it must fail fast (one attempt), not burn the whole
        // backoff budget.
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("\"not a MessageWithParts\"", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = OpencodeApiClient(makeClient(engine))
        runCatching { api.sendPrompt("s1", "hello") }
        assertEquals("deserialization failure must not be retried", 1, calls)
    }
}
