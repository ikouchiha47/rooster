package com.personalos.app.core.feed

import com.personalos.app.core.tag.Tags

/**
 * Bundled seed feeds, all verified reachable without a key.
 *
 * This is a starter set, not the strategy: the plan is place-parameterised
 * discovery (search -> find parseable feeds -> subscribe), and eventually a
 * user-editable list. See docs/research/india-feed-catalog.md.
 *
 * Every source declares its tags. There is no default: a feed that does not say
 * what it is cannot be added, because defaulting to `news` is precisely how a
 * status page's rows ended up in general news.
 */
object FeedCatalog {
    val SEEDS: List<FeedSource> =
        listOf(
            // ---- news
            newsSource(
                id = "thehindu-top",
                name = "The Hindu",
                url = "https://www.thehindu.com/feeder/default.rss",
            ),
            newsSource(
                id = "thehindu-kolkata",
                name = "The Hindu Kolkata",
                url = "https://www.thehindu.com/news/cities/kolkata/feeder/default.rss",
            ),
            newsSource(
                id = "indianexpress",
                name = "Indian Express",
                url = "https://indianexpress.com/feed/",
            ),
            // A markets desk: news that is also finance.
            newsSource(
                id = "mint-markets",
                name = "Mint Markets",
                url = "https://www.livemint.com/rss/markets",
                extraTags = setOf(Tags.FINANCE),
            ),
            newsSource(
                id = "abp-ananda-district",
                name = "ABP Ananda District",
                url = "https://bengali.abplive.com/district/feed",
                language = "bn",
            ),
            // ---- service status
            // Deliberately NOT tagged `news`: these are outages, and that tag is the
            // whole reason this field is explicit.
            FeedSource(
                id = "status-cloudflare",
                name = "Cloudflare Status",
                url = "https://www.cloudflarestatus.com/history.rss",
                primaryTag = Tags.INCIDENT,
            ),
            FeedSource(
                id = "status-aws",
                name = "AWS Status",
                url = "https://status.aws.amazon.com/rss/all.rss",
                primaryTag = Tags.INCIDENT,
            ),
        )

    /** Source string written into `events.source`, e.g. `rss:thehindu-top`. */
    const val SOURCE_PREFIX = "rss:"

    private val byId: Map<String, FeedSource> = SEEDS.associateBy { it.id }

    /** Resolves `rss:<id>` (or a bare id) back to its source, for re-tagging. */
    fun bySource(source: String): FeedSource? = byId[source.removePrefix(SOURCE_PREFIX)]
}
