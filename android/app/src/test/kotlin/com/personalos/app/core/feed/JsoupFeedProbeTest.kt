package com.personalos.app.core.feed

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PROBE, not a port — answers one question with evidence: *would jsoup remove the
 * XML sanitiser we hand-rolled in [FeedParser]?*
 *
 * FeedParser currently runs a strict JDK DOM parser and pre-repairs the input:
 * undeclared named entities, bare ampersands, and CDATA it must not touch. That is
 * three rules, each of which was discovered by a bug. jsoup is a lenient parser
 * with the full HTML5 entity set and native CDATA handling, and it is **already a
 * dependency** (used by `ArticleSummary`).
 *
 * These cases are the exact inputs that broke the strict parser. All passing means
 * the swap is worth doing; any failing names the reason it is not.
 *
 * Not asserted here: namespaces, tag case, and Atom `<link href>` semantics, which
 * are where a swap would actually hurt. See the notes in the report.
 */
class JsoupFeedProbeTest {
    private fun items(xml: String) = Jsoup.parse(xml, "", Parser.xmlParser()).select("item")

    @Test
    fun `recovers from a bare ampersand instead of rejecting the document`() {
        // This is what made the bundled India OPML parse as zero feeds.
        val xml = "<rss><channel><item><title>News & Views</title></item></channel></rss>"
        assertEquals("News & Views", items(xml).first()!!.selectFirst("title")!!.text())
    }

    @Test
    fun `keeps CDATA literal, so an ampersand inside it is not corrupted`() {
        // The case that would have regressed our own fix: inside CDATA, `&` is text.
        val xml = "<rss><channel><item><description><![CDATA[Tea & coffee]]></description></item></channel></rss>"
        assertEquals("Tea & coffee", items(xml).first()!!.selectFirst("description")!!.text())
    }

    @Test
    fun `resolves undeclared html entities`() {
        val xml = "<rss><channel><item><title>A&nbsp;B&mdash;C</title></item></channel></rss>"
        // jsoup gives a plain space for `&nbsp;` where our sanitiser gives U+00A0.
        // The difference is not observable: FeedParser.clean() normalises U+00A0 to a
        // space, so the rendered title is identical either way. What matters is that
        // the entity resolves at all rather than surviving as literal text.
        val title = items(xml).first()!!.selectFirst("title")!!.text()
        assertEquals("A B\u2014C", title)
    }

    @Test
    fun `preserves namespaced element names`() {
        // The main risk of a swap: RSS/Atom carry `content:encoded`, `dc:creator`,
        // `media:content`. In XML mode jsoup keeps the colon and the case.
        val xml = "<rss><channel><item><content:encoded>body</content:encoded></item></channel></rss>"
        val encoded = items(xml).first()!!.selectFirst("content|encoded")
        assertEquals("body", encoded?.text())
    }

    @Test
    fun `does not lowercase tag names in xml mode`() {
        // HTML mode lowercases everything, which breaks `pubDate`.
        val xml = "<rss><channel><item><pubDate>Mon, 14 Sep 2026 11:00:50 GMT</pubDate></item></channel></rss>"
        assertEquals(
            "Mon, 14 Sep 2026 11:00:50 GMT",
            items(xml).first()!!.selectFirst("pubDate")!!.text(),
        )
    }

    @Test
    fun `atom link href survives`() {
        val xml = "<feed><entry><title>T</title><link href=\"https://e.example/a\" /></entry></feed>"
        val entry = Jsoup.parse(xml, "", Parser.xmlParser()).select("entry").first()!!
        assertEquals("https://e.example/a", entry.selectFirst("link")!!.attr("href"))
    }
}
