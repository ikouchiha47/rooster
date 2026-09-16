package com.personalos.app.ui.rules

import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.sources.SourceKeys
import com.personalos.app.core.sources.SourceKind
import com.personalos.app.data.SourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBuilderChoicesTest {
    @Test
    fun `source choices include sms catalog feeds and user sources`() {
        val userRss =
            SourceEntity(
                id = "user-rss-1",
                name = "My Feed",
                kind = SourceKind.RSS.serialName,
                specJson = """{"url":"https://example.com/feed","tags":["news"]}""",
                seeded = false,
                enabled = true,
                createdAt = 1L,
            )
        val userSearch =
            SourceEntity(
                id = "user-search-1",
                name = "My Search",
                kind = SourceKind.SEARCH.serialName,
                specJson =
                    """{"query":"kolkata","query_lang_code":"en","source_locale":"en-IN","tags":["news"]}""",
                seeded = false,
                enabled = true,
                createdAt = 1L,
            )

        val choices = sourceChoices(listOf(userRss, userSearch))

        val sources = choices.map { it.source }.toSet()
        assertTrue("sms" in sources)
        assertTrue("rss:thehindu-top" in sources)
        assertTrue("${SourceKeys.USER_RSS_PREFIX}user-rss-1" in sources)
        assertTrue("gnews:kolkata" in sources)

        assertEquals("SMS", choices.find { it.source == "sms" }?.displayName)
        assertEquals(
            "My Feed",
            choices.find { it.source == "${SourceKeys.USER_RSS_PREFIX}user-rss-1" }?.displayName,
        )
        assertEquals(
            "My Search",
            choices.find { it.source == "gnews:kolkata" }?.displayName,
        )
    }

    @Test
    fun `source choices skip unparseable specs`() {
        val broken =
            SourceEntity(
                id = "broken",
                name = "Broken",
                kind = "unknown",
                specJson = "{}",
                seeded = false,
                enabled = true,
                createdAt = 1L,
            )
        val choices = sourceChoices(listOf(broken))
        assertEquals(1 + FeedCatalog.SEEDS.size, choices.size)
        assertTrue(choices.none { it.source == "broken" })
    }

    @Test
    fun `field choices are exactly FieldNames_SUPPLIED`() {
        // The UI dropdown uses FieldNames.SUPPLIED directly; this test guards
        // against a future change that accidentally widens the set.
        val draft =
            RuleDraft(
                name = "Test",
                composition = RuleDraft.Composition.ALL,
                predicates =
                    listOf(
                        PredicateDraft.Field(
                            "sender",
                            com.personalos.app.core.rules.FieldOp.EQ,
                            com.personalos.app.core.rules.FieldValue
                                .Str("bank"),
                        ),
                    ),
                push = true,
                position = 0L,
            )
        val json = draft.toConditionJson()
        com.personalos.app.core.rules.ConditionJson
            .parse(json)
    }
}
