package com.personalos.app.data

import com.personalos.app.core.calendar.CalendarProvider
import com.personalos.app.core.calendar.DirectoryFetch
import com.personalos.app.core.calendar.Region
import com.personalos.app.core.net.Http
import com.personalos.app.data.adapters.CachedBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The regions a provider covers, as choices rather than as a vocabulary to guess.
 *
 * The provider owns its own directory ([CalendarProvider.directory]): bundled or
 * published, this class only supplies the fetch, so it never names a provider or
 * a shape. Both reads go through [CachedBody] with a long window — a country list
 * changes on the order of years, so opening the picker must not mean a request.
 *
 * A failure is returned as a failure, never swallowed into an empty list: the
 * screen says the directory could not be loaded, because "no countries exist"
 * and "we could not ask" are different facts.
 */
class CalendarDirectory(
    private val cache: CachedBody,
    private val fetch: (String) -> String = { url -> Http.getText(url, accept = "text/html") },
) {
    suspend fun countries(
        provider: CalendarProvider,
        now: Long = System.currentTimeMillis(),
    ): Result<List<Region>> = withContext(Dispatchers.IO) { provider.directory.countries(caching(now)) }

    suspend fun subdivisions(
        provider: CalendarProvider,
        country: Region,
        now: Long = System.currentTimeMillis(),
    ): Result<List<Region>> = withContext(Dispatchers.IO) { provider.directory.subdivisions(country, caching(now)) }

    /** One cached body per page, shared however many times a picker is opened. */
    private fun caching(now: Long): DirectoryFetch =
        DirectoryFetch { url ->
            cache.get(
                key = "calendar-directory:$url",
                ttlMs = DIRECTORY_TTL_MS,
                now = now,
            ) { fetch(url) }
        }

    private companion object {
        /** A year's worth: a provider's country list is not a moving target. */
        const val DIRECTORY_TTL_MS = 365L * 86_400_000L
    }
}
