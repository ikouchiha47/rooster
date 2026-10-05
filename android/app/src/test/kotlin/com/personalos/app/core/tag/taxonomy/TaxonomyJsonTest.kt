package com.personalos.app.core.tag.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxonomyJsonTest {
    private val json =
        """
        {
          "id": "x", "version": 3,
          "tags": [
            {"tag":"finance","group":"subject","owner":"embedding","description":"money","terms":"bank|finance"},
            {"tag":"incident","group":"nature","owner":"embedding","description":"a disruption","terms":"outage"}
          ],
          "markers": [ {"tag":"news","description":"editorial"} ],
          "derivations": [ {"tag":"local","from":["place"],"when":"place near me"} ]
        }
        """.trimIndent()

    @Test
    fun `it parses leaves, markers and derivations`() {
        val taxonomy = TaxonomyJson.parse(json)

        assertEquals(3, taxonomy.version)
        assertEquals(listOf("finance", "incident"), taxonomy.tags.map { it.tag })
        assertEquals("news", taxonomy.markers.single().tag)
        assertEquals(listOf("place"), taxonomy.derivations.single().from)
    }

    @Test
    fun `the source excludes markers, so news is never inferred`() {
        val tags =
            TaxonomyJson
                .parse(json)
                .source()
                .definitions()
                .map { it.tag }

        assertEquals(listOf("finance", "incident"), tags)
        assertFalse("news is structural, not inferred", "news" in tags)
    }

    @Test
    fun `an overlay wins per leaf and adds new ones`() {
        val base = Taxonomy(id = "b", version = 1, tags = listOf(TagLeaf("travel", "subject", "embedding", "travel", "train")))
        val overlay =
            Taxonomy(
                id = "o",
                version = 2,
                tags =
                    listOf(
                        TagLeaf("travel", "subject", "embedding", "travel and tourism", "train|airline"),
                        TagLeaf("paper", "subject", "lexicon", "a publication", "arxiv"),
                    ),
            )

        val merged = mergeTaxonomies(base, overlay)
        val byTag = merged.tags.associateBy { it.tag }

        assertEquals(2, merged.version)
        assertEquals("train|airline", byTag.getValue("travel").terms)
        assertTrue("a new leaf is added", "paper" in byTag)
    }
}
