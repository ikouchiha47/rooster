package com.personalos.app.data.feed

import android.content.Context
import com.personalos.app.core.feed.FeedDirectory
import com.personalos.app.core.feed.FeedDirectoryJson

/**
 * Loads the feed directory from the bundled asset. Data layer because reading the
 * asset needs a [Context].
 *
 * There is no downloaded overlay: the directory ships with the app. When a
 * downloadable copy is wanted it becomes a registered **dataset** — an artifact in
 * this app's own scope — and this loader is handed the resolved file, the way
 * [com.personalos.app.data.taxonomy.TaxonomyLoader] is.
 */
object FeedDirectoryLoader {
    const val ASSET = "taxonomy/feeds.v1.json"

    fun load(context: Context): FeedDirectory? =
        runCatching {
            val text = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
            FeedDirectoryJson.parse(text)
        }.getOrNull()
}
