package soy.iko.opencode.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.RedirectResponseException
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    @Test
    fun maxAttemptsOneDoesNotRetry() = runTest {
        var calls = 0
        val error = runCatching {
            withRetryInternal(maxAttempts = 1, initialDelayMs = 0) {
                calls++
                throw IOException("boom")
            }
        }.exceptionOrNull()!!
        assertEquals("maxAttempts=1 means a single attempt only", 1, calls)
        assertTrue(error is IOException)
    }

    @Test
    fun succeedsOnLastAllowedAttempt() = runTest {
        var calls = 0
        val result = withRetryInternal(maxAttempts = 3, initialDelayMs = 0) {
            calls++
            if (calls < 3) throw IOException("transient")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun exponentialBackoffDoublesDelayBetweenAttempts() = runTest {
        var calls = 0
        val start = testScheduler.currentTime
        runCatching {
            withRetryInternal(maxAttempts = 3, initialDelayMs = 100L, jitterFactor = 0.0) {
                calls++
                throw IOException("always")
            }
        }
        // Delays: attempt 1→2 waits 100ms (100 * 2^0), attempt 2→3 waits 200ms
        // (100 * 2^1). Total virtual time elapsed = 300ms.
        assertEquals(3, calls)
        assertEquals(300L, testScheduler.currentTime - start)
    }

    @Test
    fun backoffDelayFormulaIsInitialTimesTwoToAttemptMinusOne() = runTest {
        val delays = mutableListOf<Long>()
        var calls = 0
        var lastTime = testScheduler.currentTime
        runCatching {
            withRetryInternal(maxAttempts = 4, initialDelayMs = 50L, jitterFactor = 0.0) {
                calls++
                val now = testScheduler.currentTime
                if (calls > 1) delays.add(now - lastTime)
                lastTime = now
                throw IOException("always")
            }
        }
        // Expected delays: 50, 100, 200 (50 * 2^0, 50 * 2^1, 50 * 2^2)
        assertEquals(listOf(50L, 100L, 200L), delays)
    }

    @Test
    fun symmetricJitterStaysWithinBounds() = runTest {
        // With jitterFactor = 0.2, the delay should be baseDelay ± 20%.
        // baseDelay = 100, so delay ∈ [80, 120]. Run many times to verify
        // the symmetric jitter never exceeds the bounds.
        repeat(100) {
            val start = testScheduler.currentTime
            runCatching {
                withRetryInternal(maxAttempts = 2, initialDelayMs = 100L, jitterFactor = 0.2) {
                    throw IOException("always")
                }
            }
            val elapsed = testScheduler.currentTime - start
            assertTrue("elapsed $elapsed should be >= 80", elapsed >= 80)
            assertTrue("elapsed $elapsed should be <= 120", elapsed <= 120)
        }
    }

    @Test
    fun redirectErrorIsNotRetried() = runTest {
        // RedirectResponseException is thrown when expectSuccess=true and the status is 3xx.
        // We use 300 (MultipleChoices) since MockEngine follows 301/302 by default.
        val client = HttpClient(MockEngine {
            respond("", HttpStatusCode.MultipleChoices, headersOf("Content-Length", "0"))
        }) {
            expectSuccess = true
            followRedirects = false
        }
        val redirectError = client.use { runCatching { it.get("test") }.exceptionOrNull()!! }
        assertTrue("expected RedirectResponseException, got $redirectError", redirectError is RedirectResponseException)
        var calls = 0
        val error = runCatching {
            withRetryInternal(initialDelayMs = 0) {
                calls++
                throw redirectError
            }
        }.exceptionOrNull()!!
        assertEquals("3xx must not be retried", 1, calls)
        assertTrue(error is RedirectResponseException)
    }

    @Test
    fun jitteredBackoffDoublesWithAttempt() {
        // With no jitter, backoff = initial * 2^(attempt-1)
        assertEquals(100L, jitteredBackoff(1, 100L, 0.0))
        assertEquals(200L, jitteredBackoff(2, 100L, 0.0))
        assertEquals(400L, jitteredBackoff(3, 100L, 0.0))
        assertEquals(800L, jitteredBackoff(4, 100L, 0.0))
    }

    @Test
    fun jitteredBackoffClampsToMaxDelay() {
        // At high attempts, the delay should be clamped to the configured max.
        val maxDelay = NetworkConfig.retryMaxDelayMs
        val delay = jitteredBackoff(40, 100L, 0.0)
        assertTrue("delay $delay should be clamped to max $maxDelay", delay <= maxDelay)
    }
}
