package com.personalos.app.core.resilience

/** Why an attempt failed — the only branch any policy has. */
enum class Failure { TRANSIENT, PERMANENT }

/**
 * Health of one keyed circuit across runs. `pausedUntil > now` means the circuit
 * is open. Keys are owned by the caller - a retag family, a host, a queue - this
 * type never knows what the key means.
 */
data class CircuitHealth(
    val consecutiveFailures: Int = 0,
    val pausedUntil: Long = 0L,
) {
    fun paused(now: Long): Boolean = pausedUntil > now

    companion object {
        val HEALTHY = CircuitHealth()
    }
}

/**
 * The circuit-breaker rule, pure: K consecutive transient failures — or one permanent
 * failure — open the circuit for a cooldown, so a broken key is paused, not
 * hammered every trigger. A success closes it.
 */
object CircuitBreaker {
    const val DEFAULT_THRESHOLD = 3
    const val DEFAULT_COOLDOWN_MS = 15 * 60 * 1000L

    fun onFailure(
        health: CircuitHealth,
        failure: Failure,
        now: Long,
        threshold: Int = DEFAULT_THRESHOLD,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ): CircuitHealth =
        when (failure) {
            Failure.PERMANENT -> CircuitHealth(health.consecutiveFailures + 1, now + cooldownMs)
            Failure.TRANSIENT -> {
                val failures = health.consecutiveFailures + 1
                CircuitHealth(failures, if (failures >= threshold) now + cooldownMs else health.pausedUntil)
            }
        }

    fun onSuccess(): CircuitHealth = CircuitHealth.HEALTHY
}

/** Where health lives (prefs/DB behind this interface — Dependency Inversion). */
interface CircuitStore {
    suspend fun get(key: String): CircuitHealth

    suspend fun onSuccess(key: String): CircuitHealth

    suspend fun onFailure(
        key: String,
        failure: Failure,
        now: Long,
    ): CircuitHealth
}

/** In-memory health: the default before a persistent store is wired, and in tests. */
class InMemoryCircuitStore : CircuitStore {
    private val map = mutableMapOf<String, CircuitHealth>()

    override suspend fun get(key: String): CircuitHealth = map[key] ?: CircuitHealth.HEALTHY

    override suspend fun onSuccess(key: String): CircuitHealth = CircuitBreaker.onSuccess().also { map[key] = it }

    override suspend fun onFailure(
        key: String,
        failure: Failure,
        now: Long,
    ): CircuitHealth = CircuitBreaker.onFailure(map[key] ?: CircuitHealth.HEALTHY, failure, now).also { map[key] = it }
}
