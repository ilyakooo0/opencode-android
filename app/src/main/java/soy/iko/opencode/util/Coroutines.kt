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
 * A log-safe exception summary that avoids leaking the request URL (which
 * [ClientRequestException] embeds in its message and may contain auth or paths).
 * Use this instead of logging the full exception object when it may originate
 * from a Ktor HTTP call.
 */
fun safeExceptionSummary(e: Throwable): String {
    var current: Throwable? = e
    var hops = 0
    while (current != null && hops < 8) {
        val status = (current as? io.ktor.client.plugins.ClientRequestException)?.response?.status?.value
        if (status != null) return "${current.javaClass.simpleName}($status)"
        current = current.cause
        hops++
    }
    return e.javaClass.simpleName
}
