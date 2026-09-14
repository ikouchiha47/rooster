package com.personalos.app.core.feed

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One feed discovered in an OPML file.
 *
 * [group] is the enclosing outline's label when the file is grouped — a country
 * for the India file, or an interest in the `recommended` variant (Cricket,
 * Cars, …). It is metadata, not a tag: the tagger decides what a feed publishes.
 */
data class OpmlEntry(
    val title: String,
    val url: String,
    val group: String? = null,
    val description: String? = null,
)

/**
 * Minimal OPML reader, using the same JDK DOM parser as [FeedParser].
 *
 * Deliberately dependency-free and **pure**, so it is unit-testable on the JVM and
 * can be run against the real bundled file rather than a hand-written fixture.
 *
 * Robustness matters here because OPML arrives from third parties: an unknown named
 * entity (`&nbsp;`, `&mdash;`) makes a strict XML parser throw, so input is
 * sanitised the same way feeds are. Malformed input returns an empty list rather
 * than propagating — an unparseable OPML should not crash an import.
 */
object OpmlParser {
    fun parse(opml: String): List<OpmlEntry> {
        if (opml.isBlank()) return emptyList()

        val document =
            runCatching {
                DocumentBuilderFactory
                    .newInstance()
                    .apply { isNamespaceAware = false }
                    .newDocumentBuilder()
                    .parse(InputSource(StringReader(FeedParser.sanitizeEntities(opml))))
            }.getOrNull() ?: return emptyList()

        val nodes = document.getElementsByTagName("outline")
        val entries = ArrayList<OpmlEntry>(nodes.length)
        val seen = HashSet<String>()

        for (index in 0 until nodes.length) {
            val outline = nodes.item(index) as? Element ?: continue

            val url = outline.attribute("xmlUrl") ?: continue
            // A grouping node ("India", "Cricket") has no xmlUrl; skip it.
            if (!seen.add(url)) continue

            entries +=
                OpmlEntry(
                    title = outline.attribute("title") ?: outline.attribute("text") ?: url,
                    url = url,
                    group = enclosingGroup(outline),
                    description = outline.attribute("description"),
                )
        }
        return entries
    }

    /** The nearest ancestor outline that is itself a group rather than a feed. */
    private fun enclosingGroup(element: Element): String? {
        var node = element.parentNode
        while (node != null) {
            if (node is Element && node.nodeName.equals("outline", ignoreCase = true)) {
                if (node.attribute("xmlUrl") == null) {
                    return node.attribute("text") ?: node.attribute("title")
                }
            }
            node = node.parentNode
        }
        return null
    }

    private fun Element.attribute(name: String): String? = getAttribute(name)?.trim()?.takeIf { it.isNotEmpty() }
}
