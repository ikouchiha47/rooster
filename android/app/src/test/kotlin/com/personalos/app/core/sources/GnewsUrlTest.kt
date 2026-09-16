package com.personalos.app.core.sources

import org.junit.Assert.assertEquals
import org.junit.Test

class GnewsUrlTest {
    @Test
    fun `the builder matches the exact v1 template`() {
        assertEquals(
            "https://news.google.com/rss/search?q={QUERY}&hl=en-IN&gl=IN&ceid=IN:en"
                .replace("{QUERY}", "West+Bengal"),
            GnewsUrl.build("West Bengal"),
        )
    }

    @Test
    fun `the query is url-encoded`() {
        assertEquals(
            "https://news.google.com/rss/search?q=Bengaluru+%26+Mysuru&hl=en-IN&gl=IN&ceid=IN:en",
            GnewsUrl.build("Bengaluru & Mysuru"),
        )
    }

    @Test
    fun `slugs are lowercase queries with hyphens`() {
        assertEquals("west-bengal", GnewsUrl.slug("West Bengal"))
        assertEquals("west-bengal", GnewsUrl.slug("  West   Bengal "))
        assertEquals("gnews:west-bengal", GnewsUrl.sourceFor("West Bengal"))
    }
}
