package dev.piexel.codex

import kotlinx.coroutines.CancellationException

/** Like [runCatching] for [Exception]s, but coroutine cancellation always propagates. */
internal inline fun <T> runCatchingUnlessCancelled(action: () -> T): Result<T> = try {
    Result.success(action())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
