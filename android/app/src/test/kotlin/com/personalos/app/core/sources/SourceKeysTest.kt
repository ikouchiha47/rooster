package com.personalos.app.core.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceKeysTest {
    @Test
    fun `search rows keep the stored gnews slug`() {
        val spec =
            SearchSpec(
                query = "West Bengal",
                queryLangCode = "en",
                sourceLocale = "en-IN",
                tags = setOf("news"),
            )
        assertEquals("gnews:west-bengal", SourceKeys.sourceFor("any-id", spec))
    }

    @Test
    fun `user rss rows are keyed by stable source id, not the editable name`() {
        val spec = RssSpec(url = "https://example.com/feed", tags = setOf("news"))
        val first = SourceKeys.sourceFor("ulid-1", spec)
        assertTrue(first.startsWith(SourceKeys.USER_RSS_PREFIX))
        assertEquals(first, SourceKeys.sourceFor("ulid-1", spec))
    }
}
