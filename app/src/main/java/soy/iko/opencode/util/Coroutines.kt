package soy.iko.opencode.util

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching] but rethrows [CancellationException] so structured cancellation
 * works correctly inside coroutines. Using plain [runCatching] in a coroutine swallows
 * cancellation, causing [Result.onFailure] to run on a dying scope and mutate state
 * that may never be observed (and defeating cooperative cancellation).
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * Suspend variant of [runCatchingCancellable] for blocks that themselves call suspend
 * functions. The non-suspend overload can't accept a suspend lambda.
 */
suspend inline fun <T> runCatchingCancellableSuspend(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * A log-safe exception summary that avoids leaking the request URL (which
 * [ClientRequestException] embeds in its message and may contain auth or paths).
 * Use this instead of logging the full exception object when it may originate
 * from a Ktor HTTP call.
 *
 * Extracts the HTTP status code when available — it's safe to log (not a URL) and
 * makes 4xx/5xx REST and SSE-establishment failures actionable. Walks the cause
 * chain so a [ServerResponseException] (5xx) or [SSEClientException] wrapping a
 * [ClientRequestException] still surfaces its status.
 */
fun safeExceptionSummary(e: Throwable): String {
    var current: Throwable? = e
    var hops = 0
    while (current != null && hops < 8) {
        val status = when (current) {
            is io.ktor.client.plugins.ResponseException -> current.response.status.value
            is io.ktor.client.plugins.sse.SSEClientException -> current.response?.status?.value
            else -> null
        }
        if (status != null) return "${current.javaClass.simpleName}($status)"
        current = current.cause
        hops++
    }
    return e.javaClass.simpleName
}
