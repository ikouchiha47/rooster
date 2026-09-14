package com.personalos.app.core.feed

import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal RSS 2.0 / Atom parser using the JDK/Android DOM parser.
 *
 * Deliberately dependency-free and pure, so it is unit-testable on the JVM.
 *
 * Real feeds are not strictly valid XML: they routinely use HTML entities such
 * as `&nbsp;`, `&mdash;` or `&rsquo;`, which are **not declared** in XML and
 * make a strict parser throw. Those are normalised before parsing, so one
 * sloppy entity in a feed cannot kill the whole source.
 */
object FeedParser {
    private val RFC822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
    private val ISO_BASIC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
    private val ISO_MILLIS = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ENGLISH)
    private val DATE_ONLY = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

    private val tagRe = Regex("<[^>]*>")
    private val wsRe = Regex("\\s+")
    private val entityRe = Regex("&([a-zA-Z][a-zA-Z0-9]{1,31});")

    /** XML built-ins the parser handles itself. */
    private val XML_BUILTINS = setOf("amp", "lt", "gt", "quot", "apos")

    /** Common HTML named entities, mapped to the characters they mean. */
    private val HTML_ENTITIES =
        mapOf(
            "nbsp" to " ",
            "mdash" to "\u2014",
            "ndash" to "\u2013",
            "hellip" to "\u2026",
            "lsquo" to "\u2018",
            "rsquo" to "\u2019",
            "ldquo" to "\u201C",
            "rdquo" to "\u201D",
            "bull" to "\u2022",
            "middot" to "\u00B7",
            "deg" to "\u00B0",
            "copy" to "\u00A9",
            "reg" to "\u00AE",
            "trade" to "\u2122",
            "euro" to "\u20AC",
            "pound" to "\u00A3",
            "rupee" to "\u20B9",
        )

    fun parse(
        xml: String,
        limit: Int = 40,
    ): List<FeedItem> {
        val doc =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(InputSource(StringReader(sanitizeEntities(xml))))

        val out = ArrayList<FeedItem>()

        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) out.add(readEntry(items.item(i)))

        val entries = doc.getElementsByTagName("entry")
        for (i in 0 until entries.length) out.add(readEntry(entries.item(i)))

        return out.filter { it.title.isNotBlank() }.take(limit)
    }

    /**
     * Replaces undeclared HTML entities with their characters. Unknown ones
     * become a space rather than failing the whole feed.
     */
    fun sanitizeEntities(xml: String): String =
        entityRe.replace(xml) { match ->
            val name = match.groupValues[1]
            when {
                name in XML_BUILTINS -> match.value
                else -> HTML_ENTITIES[name] ?: " "
            }
        }

    private fun readEntry(node: Node): FeedItem {
        var title: String? = null
        var link: String? = null
        var summary: String? = null
        var date: String? = null

        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            when (child.nodeName.lowercase(Locale.ROOT)) {
                "title" -> if (title == null) title = child.textContent
                "link" ->
                    if (link.isNullOrBlank()) {
                        link = child.attributes?.getNamedItem("href")?.nodeValue ?: child.textContent
                    }
                "description", "summary", "content" -> if (summary == null) summary = child.textContent
                "pubdate", "published", "updated", "date" -> if (date == null) date = child.textContent
            }
        }

        return FeedItem(
            title = clean(title),
            link = link?.trim()?.takeIf { it.isNotEmpty() },
            summary = clean(summary).take(300).takeIf { it.isNotEmpty() },
            publishedAt = parseDate(date),
        )
    }

    private fun clean(value: String?): String =
        value
            .orEmpty()
            .replace('\u00A0', ' ')
            .replace(tagRe, " ")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(wsRe, " ")
            .trim()

    fun parseDate(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalised = if (raw.endsWith("Z")) raw.dropLast(1) else raw
        val patterns = listOf(RFC822, ISO_MILLIS, ISO_BASIC, DATE_ONLY)
        for (pattern in patterns) {
            val parsed = runCatching { pattern.parse(normalised) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }
}
