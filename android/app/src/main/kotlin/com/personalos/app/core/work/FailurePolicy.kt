package com.personalos.app.core.work

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.TimeoutException

/** Re-exported from the generic rule: existing references keep compiling. */
typealias Failure = com.personalos.app.core.resilience.Failure

/** A step's own, typed failure — thrown when the step knows better than [classifyFailure]. */
class StepFailure(
    val kind: Failure,
    cause: Throwable? = null,
) : Exception(cause)

/**
 * Best-effort classification of an **untyped** throw. Pure, so it is tested without a
 * device. The unknown case is deliberately PERMANENT: retrying an error you do not
 * understand is how a run spins. A step that knows better should throw [StepFailure].
 */
fun classifyFailure(t: Throwable): Failure =
    when (t) {
        is CancellationException -> throw t
        is StepFailure -> t.kind
        is IOException, is TimeoutException -> Failure.TRANSIENT
        is IllegalArgumentException, is IllegalStateException, is SerializationException -> Failure.PERMANENT
        else -> Failure.PERMANENT
    }

/**
 * Bounded retry within a run. TRANSIENT only, capped, with backoff; PERMANENT never
 * retries. `null` means *stop*.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 250,
) {
    fun delayFor(
        attempt: Int,
        failure: Failure,
    ): Long? = if (failure == Failure.TRANSIENT && attempt < maxAttempts) baseDelayMs shl attempt else null
}
