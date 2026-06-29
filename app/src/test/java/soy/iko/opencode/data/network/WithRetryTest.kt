package soy.iko.opencode.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WithRetryTest {

    /** Produce the real exception Ktor throws for a status when expectSuccess = true. */
    private suspend fun httpError(status: HttpStatusCode): Throwable {
        val client = HttpClient(MockEngine { respond("", status, headersOf("Content-Length", "0")) }) {
            expectSuccess = true
        }
        return client.use { runCatching { it.get("test") }.exceptionOrNull()!! }
    }

    @Test
    fun returnsValueOnFirstAttempt() = runTest {
        var calls = 0
        val result = withRetryInternal { calls++; "ok" }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun retriesTransientFailureThenSucceeds() = runTest {
        var calls = 0
        val result = withRetryInternal(initialDelayMs = 0) {
            calls++
            if (calls < 3) throw IOException("boom")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun throwsAfterExhaustingAttempts() = runTest {
        var calls = 0
        val error = runCatching {
            withRetryInternal(maxAttempts = 3, initialDelayMs = 0) {
                calls++
                throw IOException("always")
            }
        }.exceptionOrNull()!!
        assertTrue("expected IOException, got $error", error is IOException)
        assertEquals(3, calls)
    }

    @Test
    fun clientErrorIsNotRetried() = runTest {
        val notFound = httpError(HttpStatusCode.NotFound)
        assertTrue("expected ClientRequestException, got $notFound", notFound is ClientRequestException)
        var calls = 0
        val error = runCatching {
            withRetryInternal(initialDelayMs = 0) {
                calls++
                throw notFound
            }
        }.exceptionOrNull()!!
        assertEquals("4xx must not be retried", 1, calls)
        assertTrue(error is ClientRequestException)
    }

    @Test
    fun serverErrorIsRetried() = runTest {
        val serverError = httpError(HttpStatusCode.InternalServerError)
        assertTrue("expected ServerResponseException, got $serverError", serverError is ServerResponseException)
        var calls = 0
        runCatching {
            withRetryInternal(maxAttempts = 3, initialDelayMs = 0) {
                calls++
                throw serverError
            }
        }
        assertEquals("5xx must be retried", 3, calls)
    }

    @Test
    fun cancellationIsRethrownImmediately() = runTest {
        var calls = 0
        val error = runCatching {
            withRetryInternal(initialDelayMs = 0) {
                calls++
                throw CancellationException("cancel")
            }
        }.exceptionOrNull()
        assertEquals("cancellation must not be retried", 1, calls)
        assertTrue("expected CancellationException, got $error", error is CancellationException)
    }
}
