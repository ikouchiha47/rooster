package com.personalos.app.data.remote

import com.personalos.app.core.feed.FeedParser
import com.personalos.app.core.net.Http

/**
 * The honest gate behind the add-a-feed form: fetches a URL the way the
 * ingestor would (same [Http.USER_AGENT], same [FeedParser]) and reports what
 * actually happened — the HTTP status code and whether the body parses as a
 * feed — without retrying past it.
 *
 * Bot gates (Cloudflare, Akamai) show up here as 403s or as 200s whose body is
 * a challenge page rather than a feed. Both are reported as-is: no retry
 * strategy on the device will clear them.
 *
 * Blocking: call from `Dispatchers.IO`. The fetch is injectable so tests prove
 * the gating without touching the network.
 */
sealed interface FeedCheck {
    data class Ok(
        val statusCode: Int,
        val itemCount: Int,
        val sampleTitle: String,
    ) : FeedCheck

    data class Err(
        val statusCode: Int?,
        val reason: String,
    ) : FeedCheck
}

object FeedVerifier {
    /** A fetched response: status code plus the body when one is worth parsing. */
    data class Raw(
        val statusCode: Int,
        val body: String?,
    )

    fun verify(
        url: String,
        fetch: (String) -> Raw = ::fetchRaw,
    ): FeedCheck {
        val raw =
            runCatching { fetch(url) }
                .getOrElse { return FeedCheck.Err(null, "No connection: ${it.message ?: "fetch failed"}") }
        if (raw.statusCode !in 200..299) return FeedCheck.Err(raw.statusCode, gateMessage(raw.statusCode))
        val items =
            runCatching { FeedParser.parse(raw.body.orEmpty()) }
                .getOrElse { return FeedCheck.Err(raw.statusCode, "HTTP ${raw.statusCode}, but the body does not parse as RSS/Atom.") }
        if (items.isEmpty()) return FeedCheck.Err(raw.statusCode, notFeedMessage(raw.body.orEmpty()))
        return FeedCheck.Ok(raw.statusCode, items.size, items.first().title)
    }

    private fun gateMessage(statusCode: Int): String =
        when (statusCode) {
            403 -> "HTTP 403 — the host is gating bots (Cloudflare/Akamai show this); it will not clear on retry."
            else -> "HTTP $statusCode — the host refused the fetch; retry later or drop it."
        }

    private fun notFeedMessage(body: String): String =
        if (body.contains("<html", ignoreCase = true)) {
            "HTTP 200, but the page is HTML, not a feed (often a challenge or block page)."
        } else {
            "HTTP 200, but no feed items found — not an RSS/Atom feed."
        }

    /** Same client behaviour as the ingestor: one fetch identity, in [Http]. */
    private fun fetchRaw(url: String): Raw {
        val response = Http.getRaw(url, TIMEOUT_MS, ACCEPT)
        return Raw(response.code, response.body.takeIf { response.code in 200..299 })
    }

    private const val TIMEOUT_MS = 12_000
    private const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml, text/xml"
}
