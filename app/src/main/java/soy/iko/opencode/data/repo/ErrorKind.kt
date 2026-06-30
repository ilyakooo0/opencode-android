package soy.iko.opencode.data.repo

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * User-facing categories for a thrown error. Extracted so the classification logic is
 * unit-testable without an Android [android.content.Context] (the mapping from a kind
 * to a localized string lives in [friendlyError]).
 */
enum class ErrorKind {
    /** Server host could not be resolved / connection refused. */
    NOT_REACHABLE,

    /** Connect or read timed out. */
    TIMEOUT,

    /** Server returned a 5xx response. */
    SERVER,

    /** Server returned a 4xx response (auth, not-found, etc.). */
    CLIENT,

    /** Any other network-level I/O failure. */
    NETWORK,

    /** Anything that isn't network-related. */
    UNKNOWN,
}

/**
 * Classify a throwable into an [ErrorKind] by inspecting concrete exception types rather
 * than string-matching class names. Unwraps [cause] chains so an exception surface-wrapped
 * by a library still classifies correctly.
 */
fun classifyError(t: Throwable): ErrorKind {
    var current: Throwable? = t
    // Walk a bounded cause chain so wrappers (e.g. an IllegalStateException around an
    // IOException) still classify by their root.
    var hops = 0
    while (current != null && hops < 8) {
        when (current) {
            is UnknownHostException, is ConnectException -> return ErrorKind.NOT_REACHABLE
            is SocketTimeoutException, is HttpRequestTimeoutException ->
                return ErrorKind.TIMEOUT
            is ServerResponseException -> return ErrorKind.SERVER
            is ClientRequestException -> return ErrorKind.CLIENT
            is RedirectResponseException -> return ErrorKind.CLIENT
            is IOException -> return ErrorKind.NETWORK
            else -> {
                // Any other ResponseException (unrecognized status family).
                if (current is ResponseException) return ErrorKind.SERVER
            }
        }
        current = current.cause
        hops++
    }
    return ErrorKind.UNKNOWN
}

/** The HTTP status code carried by a client/server error, if any. */
fun responseStatusCode(t: Throwable): Int? {
    var current: Throwable? = t
    var hops = 0
    while (current != null && hops < 8) {
        if (current is ResponseException) return current.response.status.value
        current = current.cause
        hops++
    }
    return null
}
