package com.personalos.app.data

import com.personalos.app.core.hazard.HazardSnapshot
import com.personalos.app.core.hazard.QuakeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single owner of the current hazard feed.
 *
 * A refresh replaces the snapshot only on success. On failure the previous
 * [HazardSnapshot.quakes] and `fetchedAt` survive, so a transient USGS outage
 * does not blank a map that already has data; with no previous success the
 * snapshot stays empty. [HazardSnapshot.error] reports the failure either way.
 */
class HazardRepository(
    private val source: QuakeSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAfterMs: Long = REFRESH_AFTER_MS,
) {
    private val _snapshot = MutableStateFlow(HazardSnapshot())
    val snapshot: StateFlow<HazardSnapshot> = _snapshot.asStateFlow()

    private val mutex = Mutex()
    private var lastAttemptAt = 0L

    /**
     * Emits the last good snapshot, refreshes when it is missing or stale, then
     * keeps emitting updates. Network work stays here; a composable only collects.
     */
    fun observe(): Flow<HazardSnapshot> =
        flow {
            emit(_snapshot.value)
            ensureFresh()
            emitAll(_snapshot)
        }

    /** A cheap launch path: fetches only when no fresh data exists. */
    suspend fun ensureFresh() {
        val current = _snapshot.value
        if (current.fetchedAt?.let { now() - it < refreshAfterMs } == true) return
        if (lastAttemptAt != 0L && now() - lastAttemptAt < RETRY_AFTER_MS) return
        refresh()
    }

    /** A user-visible Sync always fetches, even inside the freshness window. */
    suspend fun refresh() {
        mutex.withLock {
            lastAttemptAt = now()
            val result = runCatching { source.fetch() }
            val at = now()
            result
                .onSuccess { quakes ->
                    _snapshot.value =
                        HazardSnapshot(
                            quakes = quakes,
                            fetchedAt = at,
                        )
                }.onFailure { error ->
                    _snapshot.value =
                        _snapshot.value.copy(
                            error = error.message ?: "Hazard feed unavailable",
                        )
                }
        }
    }

    private companion object {
        /** USGS updates the past-day feed continuously; ten minutes is soon enough. */
        const val REFRESH_AFTER_MS = 10L * 60 * 1000

        /** After a failure, do not retry on every screen visit. */
        const val RETRY_AFTER_MS = 60L * 1000
    }
}
