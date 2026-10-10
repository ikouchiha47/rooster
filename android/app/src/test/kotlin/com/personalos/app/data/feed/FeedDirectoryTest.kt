package com.personalos.app.data.feed

import com.personalos.app.core.feed.FeedDirectoryJson
import com.personalos.app.core.tag.taxonomy.TaxonomyJson
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The directory is data; this ties it to the taxonomy so a feed can never point at
 * a category that does not exist (which would silently tag nothing).
 */
class FeedDirectoryTest {
    private val directory =
        FeedDirectoryJson.parse(File("src/main/assets/taxonomy/feeds.v1.json").readText())

    private val taxonomy =
        TaxonomyJson.parse(File("src/main/assets/taxonomy/taxonomy.v1.json").readText())

    @Test
    fun `every feed category is a known tag`() {
        // A feed may declare any known tag — an inferrable subject (`tech`, `travel`)
        // as much as a declared category (`news`, `politics`). It must just exist.
        val known = (taxonomy.tags + taxonomy.markers + taxonomy.categories).map { it.tag }.toSet()
        val used = directory.categories.map { it.category }.toSet()

        assertTrue("unknown categories: ${used - known}", known.containsAll(used))
    }

    @Test
    fun `a category offers its known feeds`() {
        assertTrue("tech should have suggested feeds", directory.feedsFor("tech").isNotEmpty())
        assertTrue("every feed has a url", directory.feedsFor("tech").all { it.url.startsWith("http") })
    }
}
