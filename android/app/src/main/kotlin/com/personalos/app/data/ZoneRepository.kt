package com.personalos.app.data

import com.personalos.app.core.cache.StringCache

/**
 * Persists the user's time zone id (`Asia/Kolkata`). The zone is applied at the
 * UI edge via [com.personalos.app.core.time.UserZone]; this only remembers the
 * choice so the app reads the same zone after a restart.
 *
 * Timestamps stay UTC epoch millis in the store — this never touches them.
 */
class ZoneRepository(
    private val cache: StringCache,
) {
    /** The stored zone id, or null when the user has never chosen one. */
    fun id(): String? = cache.read(KEY)?.value?.takeIf { it.isNotBlank() }

    fun save(zoneId: String) {
        cache.write(KEY, zoneId, System.currentTimeMillis())
    }

    private companion object {
        const val KEY = "time:zone"
    }
}
