package app.entesaver.util

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * A flow the screen collects that shows [fallback] and tries again later
 * when its source throws, instead of ending the app.
 *
 * WorkManager's own database may not open at all on full or damaged
 * storage. The start-up handler (EnteSaverApp) keeps that from crashing the
 * background, but every query the screen makes opens the database again and
 * throws the same way. Collected from a ViewModel, that throw ended the app
 * each time it was opened - no way in to free the space. The source is
 * started again after [retryDelayMs], growing to a minute, so the screen
 * catches up by itself once the space is back.
 */
fun <T> Flow<T>.failSoft(
    fallback: T,
    retryDelayMs: (Long) -> Long = ::failSoftDelayMs
): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException) return@retryWhen false
    emit(fallback)
    delay(retryDelayMs(attempt))
    true
}

/** Two seconds, then doubling, never more than a minute. */
fun failSoftDelayMs(attempt: Long): Long = minOf(60_000L, 2_000L shl attempt.coerceIn(0L, 5L).toInt())
