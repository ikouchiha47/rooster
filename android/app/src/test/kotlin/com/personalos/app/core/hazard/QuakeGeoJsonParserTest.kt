package com.personalos.app.core.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The USGS feed is third-party JSON: every field can be absent, null or the
 * wrong shape. These cases pin the contract the map depends on - a missing
 * magnitude is allowed, a non-point geometry is not.
 */
class QuakeGeoJsonParserTest {
    @Test
    fun `parses a point feature with lon lat and depth`() {
        val quake =
            QuakeGeoJsonParser
                .parse(
                    feature(
                        id = "ci41571656",
                        mag = "2.02",
                        place = "36 km ENE of Ensenada, B.C., MX",
                        time = "1793659628350",
                        coordinates = "[-116.25, 31.95, 14.23]",
                    ),
                ).single()

        assertEquals("ci41571656", quake.id)
        assertEquals(2.02, quake.magnitude ?: error("missing magnitude"), 0.0001)
        assertEquals("36 km ENE of Ensenada, B.C., MX", quake.place)
        assertEquals(1793659628350L, quake.time)
        assertEquals(31.95, quake.lat, 0.0001)
        assertEquals(-116.25, quake.lon, 0.0001)
        assertEquals(14.23, quake.depth ?: error("missing depth"), 0.0001)
        assertEquals("https://earthquake.usgs.gov/earthquakes/eventpage/ci41571656", quake.url)
    }

    @Test
    fun `keeps a feature whose magnitude is missing`() {
        val quake =
            QuakeGeoJsonParser
                .parse(
                    feature(
                        id = "nc75461757",
                        mag = null,
                        place = "10 km WNW of The Geysers, CA",
                        time = "1793659154060",
                        coordinates = "[-122.8, 38.8, 1.2]",
                    ),
                ).single()

        assertNull(quake.magnitude)
        assertEquals("10 km WNW of The Geysers, CA", quake.place)
        assertEquals(38.8, quake.lat, 0.0001)
    }

    @Test
    fun `skips non-point geometries`() {
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"ok","properties":{"mag":1.0,"place":"Somewhere","time":1},
               "geometry":{"type":"Point","coordinates":[10.0,20.0,3.0]}},
              {"type":"Feature","id":"line","properties":{"mag":2.0,"place":"Elsewhere","time":2},
               "geometry":{"type":"LineString","coordinates":[[10.0,20.0],[11.0,21.0]]}},
              {"type":"Feature","id":"area","properties":{"mag":3.0,"place":"Region","time":3},
               "geometry":{"type":"Polygon","coordinates":[[[10.0,20.0],[11.0,20.0],[11.0,21.0],[10.0,20.0]]]}}
            ]}
            """.trimIndent()

        val quakes = QuakeGeoJsonParser.parse(raw)

        assertEquals(listOf("ok"), quakes.map { it.id })
    }

    @Test
    fun `empty features yields an empty list`() {
        assertTrue(QuakeGeoJsonParser.parse("""{"type":"FeatureCollection","features":[]}""").isEmpty())
    }

    @Test
    fun `malformed or blank payload yields an empty list`() {
        assertTrue(QuakeGeoJsonParser.parse("").isEmpty())
        assertTrue(QuakeGeoJsonParser.parse("not json").isEmpty())
        assertTrue(QuakeGeoJsonParser.parse("""{"type":"FeatureCollection"}""").isEmpty())
    }

    @Test
    fun `skips a feature with no usable coordinates or id`() {
        val raw =
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"mag":1.0},"geometry":{"type":"Point","coordinates":[10.0]}},
              {"type":"Feature","id":"","properties":{"mag":1.0},"geometry":{"type":"Point","coordinates":[10.0,20.0]}},
              {"type":"Feature","id":"bad-lat","properties":{"mag":1.0},"geometry":{"type":"Point","coordinates":[10.0,95.0]}}
            ]}
            """.trimIndent()

        assertTrue(QuakeGeoJsonParser.parse(raw).isEmpty())
    }

    private fun feature(
        id: String,
        mag: String?,
        place: String,
        time: String,
        coordinates: String,
    ): String {
        val magnitude = mag ?: "null"
        return """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"$id",
               "properties":{"mag":$magnitude,"place":"$place","time":$time},
               "geometry":{"type":"Point","coordinates":$coordinates}}
            ]}
            """.trimIndent()
    }
}
