package com.personalos.app.core.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleSummaryTest {
    private fun page(vararg head: String) = "<html><head>${head.joinToString("")}</head><body></body></html>"

    private fun longParagraph(text: String) = "<p>$text</p>"

    // ------------------------------------------------------------ precedence

    @Test
    fun `og description wins over meta description`() {
        val html =
            page(
                """<meta property="og:description" content="${"OG".padEnd(120, 'o')}">""",
                """<meta name="description" content="${"META".padEnd(120, 'm')}">""",
            )
        assertEquals("OG".padEnd(120, 'o'), ArticleSummary.extract(html))
    }

    @Test
    fun `meta description is used when og is absent`() {
        val html = page("""<meta name="description" content="${"META".padEnd(120, 'm')}">""")
        assertEquals("META".padEnd(120, 'm'), ArticleSummary.extract(html))
    }

    @Test
    fun `a stub og description is skipped rather than stored`() {
        // "Read more" boilerplate must not win just because it is present.
        val html =
            page(
                """<meta property="og:description" content="Read more">""",
                """<meta name="description" content="${"META".padEnd(120, 'm')}">""",
            )
        assertEquals("META".padEnd(120, 'm'), ArticleSummary.extract(html))
    }

    @Test
    fun `falls back to the first substantive paragraph`() {
        val html =
            "<html><body>" +
                longParagraph("Short.") +
                longParagraph("This paragraph is long enough to be the summary of the page and it is the first one that qualifies.") +
                "</body></html>"
        assertEquals(
            "This paragraph is long enough to be the summary of the page and it is the first one that qualifies.",
            ArticleSummary.extract(html),
        )
    }

    @Test
    fun `returns null when the page yields nothing usable`() {
        assertNull(ArticleSummary.extract("<html><body><p>Too short.</p></body></html>"))
        assertNull(ArticleSummary.extract(""))
    }

    // -------------------------------------------------- entity decoding (jsoup)

    @Test
    fun `decodes the full html5 entity set, not a hand-picked few`() {
        val html =
            page(
                """<meta property="og:description" content="Caf&eacute; &amp; co &#8212; na&iuml;ve r&eacute;sum&eacute; at &minus;5 degrees, growing &times;2, which is long enough to pass the minimum length check">""",
            )
        val summary = ArticleSummary.extract(html)
        assertNotNull(summary)
        // &eacute;, &iuml;, &minus; and &times; were all absent from the previous
        // hand-written map, where each would have silently become a space.
        assertTrue("eacute -> é", summary!!.contains("Café"))
        assertTrue("iuml -> ï", summary.contains("naïve"))
        assertTrue("eacute -> é", summary.contains("résumé"))
        assertTrue("minus -> −", summary.contains("\u22125"))
        assertTrue("times -> ×", summary.contains("\u00D72"))
        assertTrue("mdash via numeric ref", summary.contains("\u2014"))
    }

    @Test
    fun `collapses whitespace and entities into one clean line`() {
        val html =
            page(
                """<meta property="og:description" content="Line  one&#10;&#10;Line    two &nbsp; with   gaps that need collapsing down to a single space wherever they appear in the summary">""",
            )
        val summary = ArticleSummary.extract(html)
        assertNotNull(summary)
        assertTrue(summary!!.startsWith("Line one Line two"))
        assertTrue("no double spaces", !summary.contains("  "))
        // &nbsp; decodes to U+00A0, which `\s` does not match - it must be
        // normalised explicitly or it rides through into the UI.
        assertTrue("nbsp must not survive", !summary.contains('\u00A0'))
    }

    // ------------------------------------------------------------- robustness

    @Test
    fun `meta attribute order and casing do not matter`() {
        val reversed =
            page("""<meta content="${"REV".padEnd(120, 'r')}" property="og:description">""")
        assertEquals("REV".padEnd(120, 'r'), ArticleSummary.extract(reversed))

        val upperCased =
            page("""<meta property="og:Description" content="${"UP".padEnd(120, 'u')}">""")
        assertEquals("UP".padEnd(120, 'u'), ArticleSummary.extract(upperCased))
    }

    @Test
    fun `script and style contents are never mistaken for a summary`() {
        val html =
            "<html><head><script>var x = '" + "j".repeat(300) + "';</script>" +
                "<style>.a{content:'" + "s".repeat(300) + "'}</style></head>" +
                "<body>" + longParagraph("The real paragraph text, which is comfortably over the minimum length threshold for a summary.") + "</body></html>"
        val summary = ArticleSummary.extract(html)
        assertNotNull(summary)
        assertTrue(summary!!.startsWith("The real paragraph text"))
    }

    @Test
    fun `result is capped at the maximum length`() {
        val html = page("""<meta property="og:description" content="${"x".repeat(2000)}">""")
        assertEquals(ArticleSummary.MAX_LENGTH, ArticleSummary.extract(html)?.length)
    }
}
