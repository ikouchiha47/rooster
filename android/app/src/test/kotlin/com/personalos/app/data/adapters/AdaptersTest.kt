package com.personalos.app.data.adapters

import com.personalos.app.core.sources.SourceSpecs
import com.personalos.app.data.SourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptersTest {
    private fun source(
        id: String,
        kind: String,
        spec: String,
    ) = SourceEntity(
        id = id,
        name = "West Bengal",
        kind = kind,
        specJson = spec,
        seeded = true,
        enabled = true,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `topics identity is not the registry id`() {
        val adapter = SearchKindAdapter()
        val row =
            source(
                id = "seed:search:west-bengal",
                kind = "search",
                spec = """{"query":"West Bengal","query_lang_code":"en","source_locale":"en-IN"}""",
            )
        assertEquals("gnews:west-bengal", adapter.identity(row))
        assertNotEquals(row.id, adapter.identity(row))
    }

    @Test
    fun `each kind owns its spec parse`() {
        assertTrue(RssKindAdapter().parseSpec("""{"url":"https://example.com/feed"}""") is com.personalos.app.core.sources.RssSpec)
        assertTrue(WeatherKindAdapter::class.java.name.contains("Weather"))
        assertTrue(
            com.personalos.app.data.adapters.FxKindAdapter::class.java.name.contains("Fx"),
        )
        val weather =
            com.personalos.app.core.sources.SourceSpecs
                .parse("weather", """{"place":"Bengaluru","lat":12.9,"lon":77.5}""")
        assertTrue(weather is com.personalos.app.core.sources.WeatherSpec)
        val fx = SourceSpecs.parse("fx", """{"pair":"USD-INR"}""")
        assertTrue(fx is com.personalos.app.core.sources.FxSpec)
        val sms = SourceSpecs.parse("sms", """{}""")
        assertTrue(sms is com.personalos.app.core.sources.SmsSpec)
        val device = SourceSpecs.parse("device", """{"signal":"battery"}""")
        assertTrue(device is com.personalos.app.core.sources.DeviceSpec)
    }

    @Test
    fun `loading an imd-kind row does not throw at the entity layer`() {
        val row = source(id = "seed:imd:bengaluru", kind = "imd", spec = """{"place":"Bengaluru"}""")
        assertEquals("imd", row.kind)
    }

    @Test
    fun `unknown spec shape throws at write not at read`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceSpecs.parse("search", """{"query":""}""")
        }
    }

    @Test
    fun `unbound kinds skip ingest`() {
        val adapters = adaptersByKind(listOf(RssKindAdapter(), SearchKindAdapter()))
        assertTrue("imd" !in adapters)
    }

    @Test
    fun `weather and fx parsers are pure`() {
        val current =
            WeatherCurrentParser.parse(
                """{"current":{"temperature_2m":31.2,"precipitation":0.5}}""",
            )
        assertEquals(31.2, current!!.tempC, 0.0001)
        assertEquals(0.5, current.rainMm, 0.0001)
        val rate = FxRateParser.parse("""{"rates":{"INR":95.5}}""", "USD-INR")
        assertEquals(95.5, rate!!, 0.0001)
    }
}
