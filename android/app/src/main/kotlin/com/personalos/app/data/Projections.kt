package com.personalos.app.data

import androidx.room.Embedded

/** Room projection: how many events came from each source. */
data class SourceCount(
    val source: String,
    val count: Int,
)

/** Room projection: how many items carry each tag. */
data class TagCount(
    val tag: String,
    val count: Int,
)

/** One row handed to the re-tagger, in insertion order. */
data class RetagCandidate(
    val rowId: Long,
    val ulid: String,
    val source: String,
    /** For feeds this is the source's own declaration (`NEWS` / `INCIDENT`). */
    val category: String,
    val title: String,
    val content: String,
)

/** Candidate for content enrichment: has URL, short/blank content, never attempted. */
data class EnrichmentCandidate(
    val id: Long,
    val ulid: String,
    val url: String,
    val content: String,
)

/**
 * An event plus every tag it carries, comma-joined by SQLite.
 *
 * Tiles need the *whole* tag set, not just the one they filtered on - a Mint
 * story read through the News tile is still `news` + `finance`, and the UI has
 * to say so.
 */
data class TaggedEvent(
    @Embedded val event: EventEntity,
    val tags: String?,
) {
    val tagList: List<String>
        get() = tags?.split(',')?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
}
