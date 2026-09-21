package com.personalos.app.data.adapters

import com.personalos.app.core.cache.StringCache
import com.personalos.app.data.GaugeFakes
import com.personalos.app.data.SourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Evidence for a claim, not a feature test.
 *
 * The claim: the store-backed adapters have **no refresh window** — every call
 * fetches, no matter how recently the previous one did. The providers they
 * replaced cached their body for six hours (`StringCache` exists for exactly
 * that: "keeps network calls off the screen-open path").
 *
 * So `ingest()` called twice in the same millisecond must NOT produce two
 * network calls. Today it produces two. The assertions below state the required
 * behaviour, so they fail until the adapters read through a cached fetch.
 */
class FetchRateProofTest {
    private val fxBody = """{"rates":{"INR":95.5,"EUR":0.86,"GBP":0.75}}"""

    private val weatherBody =
        """{"current":{"temperature_2m":22.2,"relative_humidity_2m":90,"wind_speed_10m":9.7,"precipitation":0.0,"weather_code":1},
            "daily":{"time":["2026-09-17"],"temperature_2m_max":[30.5],"temperature_2m_min":[20.5],
            "precipitation_probability_max":[86],"weather_code":[80]}}"""

    /** The providers' 6 h window, as the adapters use it. */
    private val sixHours = 6L * 60 * 60 * 1000

    private class FakeCache : StringCache {
        val values = mutableMapOf<String, StringCache.Entry>()

        override fun read(key: String): StringCache.Entry? = values[key]

        override fun write(
            key: String,
            value: String,
            at: Long,
        ) {
            values[key] = StringCache.Entry(value, at)
        }
    }

    /** A fetch that records every URL it is asked for. */
    private class Recorder(
        private val body: String,
    ) {
        val urls = mutableListOf<String>()

        fun fetch(url: String): String {
            urls += url
            return body
        }
    }

    @Test
    fun `two weather ingests a millisecond apart must not fetch twice`() =
        runBlocking {
            val fetch = Recorder(weatherBody)
            val adapter =
                WeatherKindAdapter(
                    observations = GaugeFakes.writer(GaugeFakes.events(), GaugeFakes.fields()),
                    cache = CachedBody(FakeCache()),
                    fetch = fetch::fetch,
                )
            val source =
                SourceEntity(
                    id = "seed:weather:bengaluru",
                    name = "Bengaluru",
                    kind = "weather",
                    specJson = """{"place":"Bengaluru","lat":12.9716,"lon":77.5946}""",
                    seeded = true,
                    enabled = true,
                    createdAt = 0L,
                    updatedAt = 0L,
                )

            adapter.ingest(source, now = 1_000L)
            adapter.ingest(source, now = 1_001L)

            assertEquals(
                "one refresh window covers both calls, so one fetch",
                1,
                fetch.urls.size,
            )
        }

    @Test
    fun `three fx pair sources must share one fetch, not one each`() =
        runBlocking {
            val fetch = Recorder(fxBody)
            val adapter =
                FxKindAdapter(
                    observations = GaugeFakes.writer(GaugeFakes.events(), GaugeFakes.fields()),
                    cache = CachedBody(FakeCache()),
                    fetch = fetch::fetch,
                )

            listOf("USD-INR", "EUR-INR", "GBP-INR").forEach { pair ->
                adapter.ingest(fxSource(pair), now = 1_000L)
            }

            assertEquals("all three pairs come from one body", 1, fetch.urls.toSet().size)
            assertEquals(
                "and from one network call, not three",
                1,
                fetch.urls.size,
            )
        }

    private fun fxSource(pair: String): SourceEntity =
        SourceEntity(
            id = "seed:fx:${pair.lowercase()}",
            name = pair,
            kind = "fx",
            specJson = """{"pair":"$pair"}""",
            seeded = true,
            enabled = true,
            createdAt = 0L,
            updatedAt = 0L,
        )
}
