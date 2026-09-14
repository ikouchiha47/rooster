package com.personalos.app.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {
    @Test
    fun `parses rss 2_0 items`() {
        val xml =
            """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <title>Example</title>
              <item>
                <title>Kolkata Metro clears safety trial</title>
                <link>https://example.com/a</link>
                <description>&lt;p&gt;CRS inspection passed for the Howrah stretch.&lt;/p&gt;</description>
                <pubDate>Sat, 13 Sep 2026 09:31:00 +0530</pubDate>
              </item>
              <item>
                <title>Second item</title>
                <link>https://example.com/b</link>
                <description>Body</description>
              </item>
            </channel></rss>
            """.trimIndent()

        val items = FeedParser.parse(xml)

        assertEquals(2, items.size)
        assertEquals("Kolkata Metro clears safety trial", items[0].title)
        assertEquals("https://example.com/a", items[0].link)
        // HTML is stripped from the summary.
        assertEquals("CRS inspection passed for the Howrah stretch.", items[0].summary)
        assertNotNull(items[0].publishedAt)
    }

    @Test
    fun `parses atom entries including href links`() {
        val xml =
            """
            <?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Chip export curbs tighten</title>
                <link rel="alternate" href="https://example.com/nikkei"/>
                <summary>Report cites suppliers in Japan.</summary>
                <updated>2026-09-13T07:40:00Z</updated>
              </entry>
            </feed>
            """.trimIndent()

        val items = FeedParser.parse(xml)

        assertEquals(1, items.size)
        assertEquals("Chip export curbs tighten", items[0].title)
        assertEquals("https://example.com/nikkei", items[0].link)
        assertEquals("Report cites suppliers in Japan.", items[0].summary)
        assertNotNull(items[0].publishedAt)
    }

    @Test
    fun `skips entries without a title`() {
        val xml =
            """
            <rss version="2.0"><channel>
              <item><link>https://example.com/x</link></item>
              <item><title>Kept</title></item>
            </channel></rss>
            """.trimIndent()

        val items = FeedParser.parse(xml)

        assertEquals(1, items.size)
        assertEquals("Kept", items[0].title)
    }

    @Test
    fun `decodes common entities and collapses whitespace`() {
        val xml =
            """
            <rss version="2.0"><channel>
              <item>
                <title>Bata &amp; Co</title>
                <description>Get   20&#39;% off &nbsp; today</description>
              </item>
            </channel></rss>
            """.trimIndent()

        val items = FeedParser.parse(xml)

        assertEquals("Bata & Co", items[0].title)
        assertTrue(items[0].summary!!.contains("20'% off"))
    }

    @Test
    fun `tolerates undeclared html entities that would break a strict xml parser`() {
        val xml =
            """
            <rss version="2.0"><channel>
              <item>
                <title>Metro &mdash; safety trial cleared</title>
                <description>Stretch&rsquo;s inspection passed&hellip; &unknownentity; next bulletin</description>
              </item>
            </channel></rss>
            """.trimIndent()

        val items = FeedParser.parse(xml)

        assertEquals(1, items.size)
        assertEquals("Metro \u2014 safety trial cleared", items[0].title)
        assertTrue(items[0].summary!!.contains("passed"))
    }

    @Test
    fun `respects the item limit`() {
        val items = (1..50).joinToString("") { "<item><title>t$it</title></item>" }
        val xml = "<rss version=\"2.0\"><channel>$items</channel></rss>"

        assertEquals(10, FeedParser.parse(xml, limit = 10).size)
    }

    @Test
    fun `a bare ampersand does not kill the whole document`() {
        // An unescaped `&` is not a character reference, so a strict parser rejects
        // the entire feed. The symptom is "this feed returned no items", not an error
        // - which is why the bundled India OPML parsed as 36-but-zero until this.
        val xml =
            """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <item><title>News & Views</title></item>
            </channel></rss>
            """.trimIndent()

        val items = FeedParser.parse(xml)
        assertEquals(1, items.size)
        assertEquals("News & Views", items[0].title)
    }

    @Test
    fun `real character references are not double escaped`() {
        val xml =
            """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <item><title>A &amp; B &#8211; C</title></item>
            </channel></rss>
            """.trimIndent()

        assertEquals("A & B \u2013 C", FeedParser.parse(xml)[0].title)
    }

    @Test
    fun `literal ampersands inside CDATA are left alone`() {
        // Inside CDATA, `&` is literal text. Substituting there fixes nothing and
        // pushes a visible "&amp;" into the rendered summary; three of the seven
        // live feeds carry an `&` inside CDATA and parse correctly today.
        val xml =
            """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <item><title>t</title><description><![CDATA[Tea & coffee]]></description></item>
            </channel></rss>
            """.trimIndent()

        assertEquals("Tea & coffee", FeedParser.parse(xml)[0].summary)
    }
}
