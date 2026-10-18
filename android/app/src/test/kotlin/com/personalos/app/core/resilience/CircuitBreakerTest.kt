package com.personalos.app.core.resilience

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitBreakerTest {
    // ------------------------------------------------------------- circuit-breaker

    @Test
    fun `K consecutive transient failures open the circuit`() {
        var health = CircuitHealth.HEALTHY
        repeat(2) { health = CircuitBreaker.onFailure(health, Failure.TRANSIENT, now = 0) }
        assertEquals(false, health.paused(now = 1))

        health = CircuitBreaker.onFailure(health, Failure.TRANSIENT, now = 0)
        assertEquals(true, health.paused(now = 1))

        assertEquals("cooldown expires", false, health.paused(now = CircuitBreaker.DEFAULT_COOLDOWN_MS + 1))
    }

    @Test
    fun `a permanent failure opens the circuit at once`() {
        val health = CircuitBreaker.onFailure(CircuitHealth.HEALTHY, Failure.PERMANENT, now = 0)
        assertEquals(true, health.paused(now = 1))
    }

    @Test
    fun `a success closes the circuit`() {
        assertEquals(CircuitHealth.HEALTHY, CircuitBreaker.onSuccess())
    }

    // --------------------------------------------------------------------- store

    @Test
    fun `an unknown key reads healthy`() =
        runBlocking {
            assertEquals(false, InMemoryCircuitStore().get("host:x.example").paused(now = 1))
        }

    @Test
    fun `success resets a key`() =
        runBlocking {
            val store = InMemoryCircuitStore()
            store.onFailure("host:x.example", Failure.PERMANENT, now = 0)
            assertEquals(CircuitHealth.HEALTHY, store.onSuccess("host:x.example"))
        }

    @Test
    fun `failures persist per key`() =
        runBlocking {
            val store = InMemoryCircuitStore()
            repeat(3) { store.onFailure("host:x.example", Failure.TRANSIENT, now = 0) }
            assertEquals(true, store.get("host:x.example").paused(now = 1))
            assertEquals(false, store.get("host:other.example").paused(now = 1))
        }
}
