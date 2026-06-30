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
