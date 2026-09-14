package com.personalos.app.data.remote

import com.personalos.app.core.cache.StringCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily parse was previously thrown away: the snapshot read day 0 of a
 * seven-day response and discarded the rest. These lock in the whole week.
 */
class OpenMeteoWeatherDaysTest {
    private val provider =
        OpenMeteoWeatherProvider(
            locations = listOf(WeatherLocation("Kolkata", 22.5726, 88.3639)),
            cache = NoCache,
        )

    @Test
    fun `parses the whole cached week, not just today`() {
        val days = provider.parseDays(PAYLOAD)

        assertEquals(4, days.size)
        assertEquals("2026-09-14", days[0].date)
        assertEquals(33, days[0].maxC)
        assertEquals(26, days[0].minC)
        assertEquals(10, days[0].rainChance)

        // The values that were previously discarded entirely.
        assertEquals("2026-09-16", days[2].date)
        assertEquals(80, days[2].rainChance)
        assertEquals(0, days[3].rainChance)
    }

    @Test
    fun `rounds temperatures to whole degrees`() {
        // 33.1 -> 33, 30.5 -> 31 (half up), 26.2 -> 26.
        val days = provider.parseDays(PAYLOAD)
        assertEquals(33, days[0].maxC)
        assertEquals(31, days[3].maxC)
        assertEquals(26, days[0].minC)
    }

    @Test
    fun `blank payload yields no days`() {
        assertTrue(provider.parseDays("").isEmpty())
        assertTrue(provider.parseDays("   ").isEmpty())
    }

    @Test
    fun `malformed payload yields no days rather than throwing`() {
        assertTrue(provider.parseDays("not json at all").isEmpty())
        assertTrue(provider.parseDays("""{"daily": }""").isEmpty())
    }

    @Test
    fun `a payload with no daily block yields no days`() {
        assertTrue(provider.parseDays("""{"current":{"temperature_2m":30}}""").isEmpty())
    }

    @Test
    fun `null entries degrade to zero instead of crashing`() {
        // Open-Meteo can return a trailing null in an array; a partial week beats
        // an exception.
        val days = provider.parseDays(PAYLOAD_WITH_NULLS)
        assertEquals(2, days.size)
        assertEquals(33, days[0].maxC)
        assertEquals(0, days[1].maxC)
        assertEquals(0, days[1].minC)
    }

    private object NoCache : StringCache {
        override fun read(key: String): StringCache.Entry? = null

        override fun write(
            key: String,
            value: String,
            at: Long,
        ) = Unit
    }

    private companion object {
        const val PAYLOAD =
            """
            {
              "latitude": 22.57, "longitude": 88.36, "timezone": "Asia/Kolkata",
              "current": { "temperature_2m": 32.4, "relative_humidity_2m": 64, "wind_speed_10m": 3.2 },
              "daily": {
                "time": ["2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17"],
                "temperature_2m_max": [33.1, 32.4, 31.0, 30.5],
                "temperature_2m_min": [26.2, 25.8, 25.1, 24.9],
                "precipitation_probability_max": [10, 55, 80, 0]
              }
            }
            """

        const val PAYLOAD_WITH_NULLS =
            """
            {
              "daily": {
                "time": ["2026-09-14", "2026-09-15"],
                "temperature_2m_max": [33.1, null],
                "temperature_2m_min": [26.2, null],
                "precipitation_probability_max": [10, null]
              }
            }
            """
    }
}
