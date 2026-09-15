package com.personalos.app.data

import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.theme.RadarTheme

/**
 * Persists the theme choice in the shared prefs store.
 *
 * This is persistence only - it holds no in-memory copy. The runtime owner is
 * `RadarFonts.theme` (a SnapshotState the type ramp reads); the app seeds it
 * from [load] at startup and Settings writes through [save] plus the state,
 * so the stored value and the live theme never drift into two copies.
 */
class ThemeRepository(
    private val cache: StringCache,
) {
    fun load(): RadarTheme = RadarTheme.fromId(cache.read(KEY)?.value)

    fun save(theme: RadarTheme) {
        cache.write(KEY, theme.id, System.currentTimeMillis())
    }

    companion object {
        const val KEY = "theme"
    }
}
