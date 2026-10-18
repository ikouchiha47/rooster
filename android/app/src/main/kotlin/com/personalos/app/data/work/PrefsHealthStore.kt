package com.personalos.app.data.work

import android.content.Context
import com.personalos.app.core.resilience.CircuitBreaker
import com.personalos.app.core.resilience.CircuitHealth
import com.personalos.app.core.resilience.CircuitStore
import com.personalos.app.core.resilience.Failure
import com.personalos.app.data.cache.PrefsStringCache

/**
 * Health persisted in the prefs cache, one row per family (`failures:pausedUntil`),
 * so a circuit that opened survives a process death instead of hammering a broken
 * family again on the next launch.
 */
class PrefsHealthStore(
    context: Context,
) : CircuitStore {
    private val cache = PrefsStringCache(context)

    override suspend fun get(key: String): CircuitHealth = parse(cache.read(key(key))?.value)

    override suspend fun onSuccess(key: String): CircuitHealth {
        cache.write(key(key), encode(CircuitHealth.HEALTHY), System.currentTimeMillis())
        return CircuitHealth.HEALTHY
    }

    override suspend fun onFailure(
        key: String,
        failure: Failure,
        now: Long,
    ): CircuitHealth {
        val next = CircuitBreaker.onFailure(get(key), failure, now)
        cache.write(key(key), encode(next), now)
        return next
    }

    private fun key(family: String): String = "retag:health:$family"

    private fun encode(health: CircuitHealth): String = "${health.consecutiveFailures}:${health.pausedUntil}"

    private fun parse(raw: String?): CircuitHealth {
        val parts = raw?.split(':') ?: return CircuitHealth.HEALTHY
        val failures = parts.getOrNull(0)?.toIntOrNull() ?: return CircuitHealth.HEALTHY
        val pausedUntil = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return CircuitHealth(failures, pausedUntil)
    }
}
