package com.personalos.app.core.provider

import com.personalos.app.core.model.FxRate
import com.personalos.app.core.model.Glance
import com.personalos.app.core.model.NowPlaying
import com.personalos.app.core.model.PinnedItem
import com.personalos.app.core.model.WeatherDay
import com.personalos.app.core.model.WeatherSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * One interface per Home data point. The UI depends only on these, so a sample
 * implementation can be replaced by a real source without touching the screen.
 */

interface GlanceProvider {
    fun observe(): Flow<Glance>
}

/** Tracked rates. Currency now; air/train fares later behind the same shape. */
interface FxProvider {
    fun observe(): Flow<List<FxRate>>
}

interface WeatherProvider {
    /** One snapshot per configured location; the UI paginates them. */
    fun observe(): Flow<List<WeatherSnapshot>>

    /**
     * The cached seven-day series for one place.
     *
     * Emits from **cache only** and never triggers a fetch, so opening a forecast
     * is instant and works offline. Empty when nothing is cached yet.
     */
    fun forecast(place: String): Flow<List<WeatherDay>>
}

/**
 * The device's **present** location, as weather.
 *
 * This is a seam, not a configured place: the platform location source (GPS /
 * network / last-known) is wired separately. Until it lands the default reports
 * nothing and Weather renders an honest placeholder - it must never promote one
 * of the configured places into the present slot, which is what made three
 * configured places look like two.
 */
interface LocationProvider {
    /** Weather for where the device is now, or null when no location is known. */
    fun observe(): Flow<WeatherSnapshot?>
}

interface NowPlayingProvider {
    fun observe(): Flow<NowPlaying?>
}

interface PinnedProvider {
    fun observe(): Flow<List<PinnedItem>>
}
