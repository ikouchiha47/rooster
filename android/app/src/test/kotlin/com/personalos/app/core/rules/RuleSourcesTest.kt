package com.personalos.app.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSourcesTest {
    @Test
    fun `search rows keep the stored gnews slug`() {
        val spec =
            SearchSpec(
                query = "West Bengal",
                queryLangCode = "en",
                sourceLocale = "en-IN",
                tags = setOf("news"),
            )
        assertEquals("gnews:west-bengal", RuleSources.sourceFor("any-id", spec))
    }

    @Test
    fun `user rss rows are keyed by stable rule id, not the editable name`() {
        val spec = RssSpec(url = "https://example.com/feed", tags = setOf("news"))
        val first = RuleSources.sourceFor("ulid-1", spec)
        assertTrue(first.startsWith(RuleSources.USER_RSS_PREFIX))
        assertEquals(first, RuleSources.sourceFor("ulid-1", spec))
    }
}
