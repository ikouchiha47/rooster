package com.personalos.app.core.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpmlParserTest {
    // ------------------------------------------------- against the shipped asset

    /**
     * The strongest test here: it parses the file we actually bundle, not a
     * hand-written fixture. If the asset is replaced with something the parser
     * cannot read, this fails rather than the importer failing on a device.
     */
    @Test
    fun `parses the bundled India OPML`() {
        val entries = OpmlParser.parse(bundledOpml().readText())

        assertEquals("the OPML is expected to hold 36 feeds", 36, entries.size)
        assertTrue("every entry needs a usable url", entries.all { it.url.startsWith("http") })
        assertEquals("no duplicate feeds", entries.size, entries.map { it.url }.distinct().size)
        assertEquals("grouped under the country outline", 1, entries.map { it.group }.distinct().size)
        assertEquals("India", entries.first().group)
        assertTrue("titles are populated", entries.all { it.title.isNotBlank() })
    }

    // ------------------------------------------------------------- from a fixture

    @Test
    fun `reads title, url, group and description`() {
        val entries = OpmlParser.parse(NESTED)

        val first = entries.first { it.url == "https://a.example/feed" }
        assertEquals("A Paper", first.title)
        assertEquals("India", first.group)

        // Falls back to `text` when `title` is absent.
        assertEquals("B Paper", entries.first { it.url == "https://b.example/feed" }.title)

        // `text` is used when `title` is absent; the url only when neither is.
        assertEquals("Untitled", entries.first { it.url == "https://e.example/feed" }.title)
        assertEquals("https://g.example/feed", entries.first { it.url == "https://g.example/feed" }.title)
    }

    @Test
    fun `a grouping outline is not mistaken for a feed`() {
        val entries = OpmlParser.parse(NESTED)
        assertTrue(
            "no entry should carry a blank url",
            entries.none { it.url.isBlank() },
        )
        // The nested group has no xmlUrl, so it must not appear as an entry...
        assertNull(entries.find { it.title == "No url grouping" })
        // ...but it must still label the feed inside it.
        assertEquals("No url grouping", entries.first { it.url == "https://c.example/feed" }.group)
    }

    @Test
    fun `a feed outside any group has a null group`() {
        assertNull(OpmlParser.parse(NESTED).first { it.url == "https://f.example/feed" }.group)
    }

    @Test
    fun `the same url is not imported twice`() {
        val urls = OpmlParser.parse(NESTED).map { it.url }
        assertEquals(urls.size, urls.distinct().size)
    }

    @Test
    fun `undeclared named entities do not break parsing`() {
        // `&nbsp;` is not declared in XML. A strict parser throws; OPML from third
        // parties is sloppy, so this must survive.
        val entries = OpmlParser.parse(NESTED)
        assertEquals(7, entries.size)
        assertTrue(entries.first { it.url == "https://d.example/feed" }.description!!.isNotBlank())
    }

    @Test
    fun `malformed or empty input yields nothing rather than throwing`() {
        assertTrue(OpmlParser.parse("").isEmpty())
        assertTrue(OpmlParser.parse("   ").isEmpty())
        assertTrue(OpmlParser.parse("not xml at all").isEmpty())
        assertTrue(OpmlParser.parse("<opml><body><outline").isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    /** Walk up from the test working directory to find the bundled asset. */
    private fun bundledOpml(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/assets/feeds/awesome-rss-feeds-india.opml") }
            .firstOrNull { it.exists() }
            ?: error("bundled OPML not found from ${File(".").absolutePath}")

    private companion object {
        val NESTED =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="1.0">
              <head><title>Test</title></head>
              <body>
                <outline text="India" title="India">
                  <outline text="A Paper" title="A Paper" xmlUrl="https://a.example/feed" type="rss" />
                  <outline title="B Paper" xmlUrl="https://b.example/feed" type="rss" />
                  <outline text="No url grouping">
                    <outline text="C Paper" xmlUrl="https://c.example/feed" type="rss" />
                  </outline>
                  <outline text="Duplicate" xmlUrl="https://a.example/feed" type="rss" />
                  <outline text="Entity" xmlUrl="https://d.example/feed" description="has an &nbsp; entity" type="rss" />
                  <outline text="Untitled" xmlUrl="https://e.example/feed" />
                  <outline xmlUrl="https://g.example/feed" />
                </outline>
                <outline text="Loose feed" xmlUrl="https://f.example/feed" type="rss" />
              </body>
            </opml>
            """.trimIndent()
    }
}
