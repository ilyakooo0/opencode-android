package soy.iko.opencode.data.repo

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorKindTest {

    @Test
    fun unknownHostIsNotReachable() {
        assertEquals(ErrorKind.NOT_REACHABLE, classifyError(UnknownHostException("nope")))
    }

    @Test
    fun connectRefusedIsNotReachable() {
        assertEquals(ErrorKind.NOT_REACHABLE, classifyError(ConnectException("refused")))
    }

    @Test
    fun socketTimeoutIsTimeout() {
        assertEquals(ErrorKind.TIMEOUT, classifyError(SocketTimeoutException("read timed out")))
    }

    @Test
    fun httpRequestTimeoutIsTimeout() {
        assertEquals(ErrorKind.TIMEOUT, classifyError(HttpRequestTimeoutException(HttpRequestBuilder())))
    }

    @Test
    fun genericIoIsNetwork() {
        assertEquals(ErrorKind.NETWORK, classifyError(IOException("broken pipe")))
    }

    @Test
    fun unrelatedThrowableIsUnknown() {
        assertEquals(ErrorKind.UNKNOWN, classifyError(IllegalStateException("boom")))
    }

    @Test
    fun unwrapsCauseChainToClassifyRoot() {
        // A library may surface a network failure wrapped in a non-IO exception.
        val wrapped = RuntimeException("request failed", UnknownHostException("nested"))
        assertEquals(ErrorKind.NOT_REACHABLE, classifyError(wrapped))
    }

    @Test
    fun doesNotLoopInfinitelyOnCyclicCause() {
        // A self-referential cause chain must be terminated by the bounded hop count,
        // not followed into a stack overflow.
        val cyclic: Throwable = object : Throwable("cyclic") {
            override val cause: Throwable? get() = this
        }
        assertEquals(ErrorKind.UNKNOWN, classifyError(cyclic))
    }
}
