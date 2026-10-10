package com.personalos.app.core.feed

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One suggested feed in the directory. */
@Serializable
data class DirectoryFeed(
    val name: String,
    val url: String,
    val kind: String = "rss",
)

/** A category and the feeds that belong to it. `category` is a declared tag. */
@Serializable
data class DirectoryCategory(
    val category: String,
    val feeds: List<DirectoryFeed> = emptyList(),
)

/**
 * The feed directory: which sources exist, grouped by category. Data, not code —
 * adding a feed is a row in `feeds.v1.json`. A category is a declared tag from the
 * taxonomy, so picking one both tags the feed and offers its known sources.
 */
@Serializable
data class FeedDirectory(
    val id: String,
    val version: Int,
    val categories: List<DirectoryCategory> = emptyList(),
) {
    fun feedsFor(category: String): List<DirectoryFeed> = categories.firstOrNull { it.category == category }?.feeds.orEmpty()
}

object FeedDirectoryJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun parse(text: String): FeedDirectory = json.decodeFromString(text)
}
