package com.personalos.app.data.adapters

import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.data.ObservationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastTest {
    private val weekJson =
        """
        {"latitude":12.97,"longitude":77.59,"timezone":"Asia/Kolkata",
         "current":{"time":"2026-09-17T22:00","temperature_2m":22.2,"relative_humidity_2m":90.0,"wind_speed_10m":9.7,"precipitation":0.0,"weather_code":1},
         "daily":{"time":["2026-09-17","2026-09-18"],
           "temperature_2m_max":[30.5,30.6],"temperature_2m_min":[20.5,20.6],
           "precipitation_probability_max":[86,69],"weather_code":[80,55]}}
        """.trimIndent()

    @Test
    fun `current parses the wide reply`() {
        val current = WeatherCurrentParser.parse(weekJson)!!
        assertEquals(22.2, current.tempC, 0.0001)
        assertEquals(90.0, current.humidityPct, 0.0001)
        assertEquals(9.7, current.windKmh, 0.0001)
        assertEquals(1.0, current.weatherCode!!, 0.0001)
    }

    @Test
    fun `week parses each day with its code`() {
        val days = WeatherWeekParser.parse(weekJson)
        assertEquals(2, days.size)
        assertEquals("2026-09-17", days[0].date)
        assertEquals(30.5, days[0].maxC, 0.0001)
        assertEquals(20.5, days[0].minC, 0.0001)
        assertEquals(86.0, days[0].rainChance, 0.0001)
        assertEquals(80.0, days[0].weatherCode!!, 0.0001)
    }

    @Test
    fun `fx crosses derive from one usd-base reply`() {
        val all = FxRateParser.parseAll("""{"rates":{"INR":95.5,"EUR":0.86,"GBP":0.75}}""")
        assertEquals(95.5, FxRateParser.rateFor("USD-INR", all)!!, 0.0001)
        assertEquals(95.5 / 0.86, FxRateParser.rateFor("EUR-INR", all)!!, 0.0001)
        assertEquals(95.5 / 0.75, FxRateParser.rateFor("GBP-INR", all)!!, 0.0001)
        // A missing leg is absent, never zero.
        assertEquals(null, FxRateParser.rateFor("EUR-INR", mapOf("INR" to 95.5)))
        assertEquals(null, FxRateParser.rateFor("USD-XYZ", all))
    }

    @Test
    fun `keyed forecast writes replace the same date`() =
        runBlocking {
            val events =
                com.personalos.app.data.GaugeFakes
                    .events()
            val fields =
                com.personalos.app.data.GaugeFakes
                    .fields()
            val writer =
                com.personalos.app.data.GaugeFakes
                    .writer(events, fields)
            val key = "weather:bengaluru:fc:2026-09-18"

            fun day(max: Double) =
                ObservationWriter.Observation(
                    identity = key,
                    timestamp = 1L,
                    title = "Bengaluru",
                    content = "2026-09-18",
                    topicTag = "weather",
                    fields = mapOf("temp_max_c" to FieldValue.Num(max)),
                )
            val ulid1 = writer.writeKeyed(key, day(30.6))
            val ulid2 = writer.writeKeyed(key, day(31.0))
            assertEquals(ulid1, ulid2)
            assertEquals(1, events.rows.count { it.source == key })
            assertEquals(31.0, fields.forItem(ulid1).single { it.name == "temp_max_c" }.valueNum!!, 0.0001)
        }

    @Test
    fun `forecast week maps rows to day cells oldest first`() =
        runBlocking {
            val events =
                com.personalos.app.data.GaugeFakes
                    .events()
            val fields =
                com.personalos.app.data.GaugeFakes
                    .fields()
            val writer =
                com.personalos.app.data.GaugeFakes
                    .writer(events, fields)
            for ((date, max) in listOf("2026-09-18" to 30.6, "2026-09-17" to 30.5)) {
                val key = "weather:bengaluru:fc:$date"
                writer.writeKeyed(
                    key,
                    ObservationWriter.Observation(
                        identity = key,
                        timestamp = 1L,
                        title = "Bengaluru",
                        content = date,
                        topicTag = "weather",
                        fields =
                            mapOf(
                                "temp_max_c" to FieldValue.Num(max),
                                "temp_min_c" to FieldValue.Num(20.5),
                                "rain_chance" to FieldValue.Num(86.0),
                            ),
                    ),
                )
            }
            // A current-temp row must never leak into the week.
            writer.write(
                ObservationWriter.Observation(
                    identity = "weather:bengaluru",
                    timestamp = 2L,
                    title = "Bengaluru",
                    content = "22.2C",
                    topicTag = "weather",
                    fields = mapOf("temp_c" to FieldValue.Num(22.2)),
                ),
            )
            val repo = ObservationRepository(events, fields)
            val week = repo.forecastWeek("bengaluru")
            assertEquals(listOf("2026-09-17", "2026-09-18"), week.map { it.date })
            assertEquals(30, week[0].maxC)
            assertEquals(86, week[0].rainChance)
        }

    @Test
    fun `weather adapter writes current plus every forecast day`() =
        runBlocking {
            val events =
                com.personalos.app.data.GaugeFakes
                    .events()
            val fields =
                com.personalos.app.data.GaugeFakes
                    .fields()
            val writer =
                com.personalos.app.data.GaugeFakes
                    .writer(events, fields)
            val adapter = WeatherKindAdapter(writer, CachedBody(FreshCache()), fetch = { weekJson })
            val row =
                com.personalos.app.data.SourceEntity(
                    id = "seed:weather:bengaluru",
                    name = "Bengaluru",
                    kind = "weather",
                    specJson = """{"place":"Bengaluru","lat":12.9,"lon":77.5}""",
                    seeded = true,
                    enabled = true,
                    createdAt = 0L,
                    updatedAt = 0L,
                )
            assertEquals(1 + 2, adapter.ingest(row, now = 1_000L))
            assertTrue(events.rows.any { it.source == "weather:bengaluru" })
            assertTrue(events.rows.any { it.source == "weather:bengaluru:fc:2026-09-17" })
            val current = fields.rows.single { it.name == "humidity_pct" }
            assertEquals(90.0, current.valueNum!!, 0.0001)
            assertTrue(fields.rows.any { it.name == "weather_code" })
        }

    @Test
    fun `fx adapter writes its pair from the shared reply`() =
        runBlocking {
            val events =
                com.personalos.app.data.GaugeFakes
                    .events()
            val fields =
                com.personalos.app.data.GaugeFakes
                    .fields()
            val writer =
                com.personalos.app.data.GaugeFakes
                    .writer(events, fields)
            val adapter =
                FxKindAdapter(
                    writer,
                    CachedBody(FreshCache()),
                    fetch = { """{"rates":{"INR":95.5,"EUR":0.86}}""" },
                )
            val row =
                com.personalos.app.data.SourceEntity(
                    id = "seed:fx:eur-inr",
                    name = "EUR-INR",
                    kind = "fx",
                    specJson = """{"pair":"EUR-INR"}""",
                    seeded = true,
                    enabled = true,
                    createdAt = 0L,
                    updatedAt = 0L,
                )
            assertEquals(1, adapter.ingest(row, now = 1_000L))
            val stored = fields.rows.single { it.name == "rate" }
            assertEquals(95.5 / 0.86, stored.valueNum!!, 0.0001)
        }

    /** A cache that never holds anything: one ingest, one fetch. */
    private class FreshCache : StringCache {
        override fun read(key: String): StringCache.Entry? = null

        override fun write(
            key: String,
            value: String,
            at: Long,
        ) = Unit
    }
}
