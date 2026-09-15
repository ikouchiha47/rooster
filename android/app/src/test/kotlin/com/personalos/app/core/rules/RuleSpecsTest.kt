package com.personalos.app.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleSpecsTest {
    // ------------------------------------------------------------------- rss

    @Test
    fun `rss accepts url and tags`() {
        val spec =
            RuleSpecs.parse(
                RuleKind.RSS,
                """{"url": "https://www.thehindu.com/feeder/default.rss", "tags": ["news"]}""",
            ) as RssSpec

        assertEquals("https://www.thehindu.com/feeder/default.rss", spec.url)
        assertEquals(setOf("news"), spec.tags)
    }

    @Test
    fun `rss keeps multi-valued tags`() {
        val spec =
            RuleSpecs.parse(
                RuleKind.RSS,
                """{"url": "https://www.livemint.com/rss/markets", "tags": ["finance", "news"]}""",
            ) as RssSpec

        assertEquals(setOf("finance", "news"), spec.tags)
    }

    @Test
    fun `rss missing url is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse(RuleKind.RSS, """{"tags": ["news"]}""")
        }
    }

    @Test
    fun `rss non-http url is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse(RuleKind.RSS, """{"url": "ftp://example.com/feed", "tags": ["news"]}""")
        }
    }

    @Test
    fun `rss unknown keys are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse(
                RuleKind.RSS,
                """{"url": "https://example.com/feed.xml", "tags": ["news"], "query": "Kolkata"}""",
            )
        }
    }

    @Test
    fun `rss missing tags defaults to news`() {
        val spec =
            RuleSpecs.parse(
                RuleKind.RSS,
                """{"url": "https://example.com/feed.xml"}""",
            ) as RssSpec

        assertEquals(setOf("news"), spec.tags)
    }

    // ---------------------------------------------------------------- search

    @Test
    fun `search accepts the full v1 shape`() {
        val spec =
            RuleSpecs.parse(
                RuleKind.SEARCH,
                """{"query": "West Bengal", "query_lang_code": "en", "source_locale": "en-IN", "tags": ["news"]}""",
            ) as SearchSpec

        assertEquals("West Bengal", spec.query)
        assertEquals("en", spec.queryLangCode)
        assertEquals("en-IN", spec.sourceLocale)
        assertEquals(setOf("news"), spec.tags)
    }

    @Test
    fun `search missing query is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse(
                RuleKind.SEARCH,
                """{"query_lang_code": "en", "source_locale": "en-IN", "tags": ["news"]}""",
            )
        }
    }

    @Test
    fun `search only en and hi query languages pass`() {
        RuleSpecs.parse(
            RuleKind.SEARCH,
            """{"query": "x", "query_lang_code": "hi", "source_locale": "hi-IN", "tags": ["news"]}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse(
                RuleKind.SEARCH,
                """{"query": "x", "query_lang_code": "bn", "source_locale": "en-IN", "tags": ["news"]}""",
            )
        }
    }

    @Test
    fun `search missing locale is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse(
                RuleKind.SEARCH,
                """{"query": "Kolkata", "query_lang_code": "en", "tags": ["news"]}""",
            )
        }
    }

    @Test
    fun `search unknown keys are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse(
                RuleKind.SEARCH,
                """{"query": "Kolkata", "query_lang_code": "en", "source_locale": "en-IN", "tags": ["news"], "url": "https://example.com"}""",
            )
        }
    }

    @Test
    fun `search blank tags fall back to news`() {
        val spec =
            RuleSpecs.parse(
                RuleKind.SEARCH,
                """{"query": "Kolkata", "query_lang_code": "en", "source_locale": "en-IN", "tags": ["", "  "]}""",
            ) as SearchSpec

        assertEquals(setOf("news"), spec.tags)
    }

    // ----------------------------------------------------------------- kinds

    @Test
    fun `an unknown kind is rejected, never defaulted`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse("scrape", """{"url": "https://example.com"}""")
        }
    }

    @Test
    fun `a non-object spec is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse(RuleKind.RSS, """["https://example.com/feed.xml"]""")
        }
    }

    @Test
    fun `malformed json is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSpecs.parse(RuleKind.RSS, """{"url": """)
        }
    }

    @Test
    fun `kind strings resolve exactly`() {
        assertEquals(RuleKind.RSS, RuleKind.from("rss"))
        assertEquals(RuleKind.SEARCH, RuleKind.from("search"))
        assertEquals(null, RuleKind.from("RSS"))
        assertEquals(null, RuleKind.from("rapidapi"))
    }
}
