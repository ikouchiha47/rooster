package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** Room projection: how many events came from each source. */
data class SourceCount(
    val source: String,
    val count: Int,
)

/** One day bucket of a tag read: the UTC day start plus how many items it holds. */
data class DayHeader(
    val dayStart: Long,
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

/** One row handed to the mention backfiller, in insertion order. */
data class MentionCandidate(
    val rowId: Long,
    val ulid: String,
    val title: String,
    val content: String,
)

/** Candidate for content enrichment: has URL, short/blank content, never attempted. */
data class EnrichmentCandidate(
    val id: Long,
    val ulid: String,
    val url: String,
    val content: String,
    /** Carried so a rule-evaluation seed can be built when the text grows (R3). */
    val source: String,
    val title: String,
)

/**
 * One bounded-preview candidate: the identity and text the evaluator needs, plus
 * the publish timestamp the hit sample renders.
 *
 * Tags, mentions and typed fields are deliberately absent - [RuleWriter] loads
 * those from the store during materialisation, so the store stays the one owner
 * of each fact (ADR 0003 §7).
 */
data class RulePreviewCandidate(
    /** `events.ulid`. */
    @ColumnInfo(name = "item_id") val itemId: String,
    /** `events.source` - the source's identity, not its transport. */
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    val content: String,
    /** `events.timestamp` - publish time, for ordering and the hit row. */
    val timestamp: Long,
)

/** One item's tag, for the batched rule-evaluation read. */
data class ItemTagRow(
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "tag") val tag: String,
)

/** One series sample: the numeric value plus when it was observed. */
data class SeriesSample(
    @ColumnInfo(name = "value_num") val valueNum: Double?,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
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
