package com.personalos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class PlacesImporterTest {
    // ------------------------------------------------- against the shipped asset

    /**
     * The strongest test here: it parses the file we actually bundle, not a
     * hand-written fixture. If the asset is regenerated in a shape the
     * importer cannot read, this fails rather than the seeder failing silent
     * on a device.
     */
    @Test
    fun `parses the bundled places asset`() {
        val places = PlacesImporter.load(GZIPInputStream(bundledPlaces().inputStream()))

        assertTrue(
            "the gazetteer holds the admin rows plus every town >= POP_MIN " +
                "(12,600 at a 10k cutoff, 14,639 at 1k); far fewer means a " +
                "truncated or misbuilt asset",
            places.size > 10_000,
        )
        assertTrue("ids are geonameids", places.all { it.id.all(Char::isDigit) })
        assertTrue("populations are never negative (admin rows may be 0)", places.all { it.population >= 0 })
    }

    @Test
    fun `the West Bengal Berhampore row parses with its real columns`() {
        val places = PlacesImporter.load(GZIPInputStream(bundledPlaces().inputStream()))

        val berhampore = places.first { it.id == "12680729" }
        assertEquals("Berhampore", berhampore.name)
        assertEquals("Berhampore", berhampore.ascii)
        assertEquals("A", berhampore.fclass)
        assertEquals("ADM3", berhampore.fcode)
        assertEquals(642110, berhampore.population)

        // ...and it is not the Odisha Berhampur, one letter away.
        val berhampur = places.first { it.id == "12681036" }
        assertEquals("Berhampur", berhampur.name)
    }

    @Test
    fun `the asset carries non-Latin alternates`() {
        val places = PlacesImporter.load(GZIPInputStream(bundledPlaces().inputStream()))

        assertTrue(
            "at least one row must carry a Devanagari alternate",
            places.any { place ->
                place.alternates.split('|').any { it.any { ch -> ch in '\u0900'..'\u097F' } }
            },
        )
    }

    // ------------------------------------------------------------- from a fixture

    @Test
    fun `malformed and short rows are skipped, never thrown`() {
        val places =
            PlacesImporter.parseLines(
                sequenceOf(
                    "",
                    "only\tthree\tcols",
                    "\t\t\t\t\t\t\t\t\t\t\t\t",
                    "abc\tBad Lat\tBad Lat\tnot-a-number\t74.0\tP\tPPL\t12\t1\t2\tx\t",
                    "999\tNo Pop\tNo Pop\t33.0\t74.0\tP\tPPL\t12\t1\t2\tnot-a-number\t",
                    "123\tGood Town\tGood Town\t33.0\t74.0\tP\tPPL\t12\t1\t2\t100\tGood|गुड",
                ),
            )

        assertEquals(1, places.size)
        assertEquals("123", places[0].id)
        assertEquals("Good|गुड", places[0].alternates)
    }

    // ------------------------------------------------------------------ helpers

    /** Walk up from the test working directory to find the bundled asset. */
    private fun bundledPlaces(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/assets/places.dat") }
            .firstOrNull { it.exists() }
            ?: error("bundled places.dat not found from ${File(".").absolutePath}")
}
