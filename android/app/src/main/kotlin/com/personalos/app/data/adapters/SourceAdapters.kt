package com.personalos.app.data.adapters

import com.personalos.app.core.sources.RssSpec
import com.personalos.app.core.sources.SearchSpec
import com.personalos.app.data.SourceEntity

/**
 * `kind = "rss"` / `"search"`: parsing and identity live on the adapter
 * (REQ-ING-13/14). Polling stays in `FeedIngestor.refreshRssSources` /
 * `refreshSearchSources` — the working path — so this slice changes no fetch
 * behaviour; the adapter map is what names the kind and its identity.
 */
class RssKindAdapter : KindAdapter {
    override val kindId: String = "rss"

    override suspend fun ingest(
        source: SourceEntity,
        now: Long,
    ): Int = 0

    fun specOf(source: SourceEntity): RssSpec = parseSpec(source.specJson) as RssSpec
}

class SearchKindAdapter : KindAdapter {
    override val kindId: String = "search"

    override suspend fun ingest(
        source: SourceEntity,
        now: Long,
    ): Int = 0

    fun specOf(source: SourceEntity): SearchSpec = parseSpec(source.specJson) as SearchSpec
}
