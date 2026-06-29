package soy.iko.opencode.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
}
