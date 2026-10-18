package com.personalos.app.core.work

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class FailurePolicyTest {
    // ---------------------------------------------------------------- classification

    @Test
    fun `io and timeouts are transient`() {
        assertEquals(Failure.TRANSIENT, classifyFailure(IOException("net")))
    }

    @Test
    fun `bugs and bad input are permanent`() {
        assertEquals(Failure.PERMANENT, classifyFailure(IllegalStateException("bug")))
        assertEquals(Failure.PERMANENT, classifyFailure(SerializationException("bad")))
    }

    @Test
    fun `an unknown error is permanent — never retried blindly`() {
        assertEquals(Failure.PERMANENT, classifyFailure(RuntimeException("who knows")))
    }

    @Test
    fun `a step's own typed failure wins`() {
        assertEquals(Failure.TRANSIENT, classifyFailure(StepFailure(Failure.TRANSIENT)))
    }

    @Test
    fun `cancellation is not an error — it propagates`() {
        assertThrows(CancellationException::class.java) { classifyFailure(CancellationException("stop")) }
    }

    // ----------------------------------------------------------------------- retry

    @Test
    fun `transient retries with growing backoff, then stops`() {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100)
        assertEquals(100L, policy.delayFor(0, Failure.TRANSIENT))
        assertEquals(200L, policy.delayFor(1, Failure.TRANSIENT))
        assertEquals(400L, policy.delayFor(2, Failure.TRANSIENT))
        assertNull("capped at maxAttempts", policy.delayFor(3, Failure.TRANSIENT))
    }

    @Test
    fun `permanent never retries`() {
        assertNull(RetryPolicy().delayFor(0, Failure.PERMANENT))
    }
}
