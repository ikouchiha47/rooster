package com.personalos.app.core.mention

import com.personalos.app.data.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionExtractorTest {
    private fun extractor(): MentionExtractor = MentionExtractor(testIndex())

    @Test
    fun `floods in Berhampore hits the West Bengal row`() {
        val hits = extractor().extract("floods in Berhampore displace thousands")

        assertEquals(1, hits.size)
        assertEquals(MentionKind.PLACE, hits[0].kind)
        assertEquals("12680729", hits[0].entityId)
        assertEquals("Berhampore", hits[0].surface)
    }

    @Test
    fun `Berhampur resolves to Odisha, not West Bengal`() {
        val hits = extractor().extract("cyclone landfall near Berhampur")

        assertEquals(1, hits.size)
        assertEquals(MentionKind.PLACE, hits[0].kind)
        assertEquals("12681036", hits[0].entityId)
    }

    @Test
    fun `punch above its weight yields no place`() {
        val hits = extractor().extract("this startup punches above its weight")

        assertTrue("bare 'punch' is the verb, not the J&K town", hits.none { it.kind == MentionKind.PLACE })
    }

    @Test
    fun `a Devanagari alternate matches`() {
        val hits = extractor().extract("पुणे में भारी बारिश")

        assertEquals(1, hits.size)
        assertEquals(MentionKind.PLACE, hits[0].kind)
        assertEquals("1259229", hits[0].entityId)
    }

    @Test
    fun `From AAP to BJP to Congress yields two party mentions`() {
        // Bare "Congress" no longer matches: worldwide it reads as the US
        // legislature, so the alias was removed when the registry went
        // worldwide. "Congress Party" and "INC" still resolve (next test).
        val hits = extractor().extract("From AAP to BJP to Congress").filter { it.kind == MentionKind.PARTY }

        assertEquals(setOf("aap", "bjp"), hits.map { it.entityId }.toSet())
    }

    @Test
    fun `Congress Party and INC still resolve to congress`() {
        val hits =
            extractor().extract("Congress Party wins Karnataka, says INC").filter { it.kind == MentionKind.PARTY }

        assertEquals(setOf("congress"), hits.map { it.entityId }.toSet())
    }

    @Test
    fun `initials alone yield no party`() {
        assertTrue(extractor().extract("SP").isEmpty())
        assertTrue(extractor().extract("NC").isEmpty())
    }

    @Test
    fun `ordinary words that happen to be town names yield no place`() {
        // Each of these fired on device before it was stopworded: "along"
        // on "along with", "men" on "men's team", "met" on the verb, and
        // "ali"/"kant"/"patra" on surnames. All are real gazetteer towns of
        // 2k–27k people; the collision cost dwarfs the match value.
        val index =
            PlaceIndex(
                listOf(
                    place("1", "Along", "Along", 18425, ""),
                    place("2", "Men", "Men", 3174, ""),
                    place("3", "Ali", "Ali", 27169, ""),
                    place("4", "Kant", "Kant", 24430, ""),
                    place("5", "Patra", "Patra", 9536, ""),
                    place("6", "Met", "Met", 2107, ""),
                ),
            )
        val hits =
            MentionExtractor(index)
                .extract("Gold prices rose along with silver as men met Ali Kant Patra")
                .filter { it.kind == MentionKind.PLACE }

        assertTrue("expected no place hits, got $hits", hits.isEmpty())
    }

    @Test
    fun `unknown text yields nothing`() {
        assertTrue(extractor().extract("the quick brown fox jumps").isEmpty())
        assertTrue(extractor().extract("").isEmpty())
        assertTrue(extractor().extract("   ").isEmpty())
    }

    @Test
    fun `the longer alias wins at the same position`() {
        val hits = extractor().extract("Communist Party of India (Marxist) rally").filter { it.kind == MentionKind.PARTY }

        assertEquals(1, hits.size)
        assertEquals("cpi-m", hits[0].entityId)
    }

    private companion object {
        fun place(
            id: String,
            name: String,
            ascii: String,
            population: Long,
            alternates: String,
        ): PlaceEntity =
            PlaceEntity(
                id = id,
                name = name,
                ascii = ascii,
                lat = 0.0,
                lon = 0.0,
                fclass = "P",
                fcode = "PPL",
                admin1 = "",
                population = population,
                alternates = alternates,
            )

        fun testIndex(): PlaceIndex =
            PlaceIndex(
                listOf(
                    place("12680729", "Berhampore", "Berhampore", 642110, ""),
                    place("12681036", "Berhampur", "Berhampur", 57614, ""),
                    place("1259229", "Pune", "Pune", 2935744, "Poona|पुणे"),
                    place("1167718", "Pūnch", "Punch", 28197, "پونچھ"),
                ),
            )
    }
}
