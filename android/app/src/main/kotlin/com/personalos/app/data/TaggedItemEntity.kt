package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * "Tagger [taggerId] processed item [itemId]" — written even when the result was
 * an empty tag set.
 *
 * `item_tags` is append-only and only holds produced tags, so it cannot tell
 * "the new tagger found nothing" from "the new tagger has not reached this item".
 * Without this marker, `item_tags_current` would fall back to a previous tagger
 * forever for an item the active tagger deliberately left untagged.
 */
@Entity(
    tableName = "tagged_items",
    primaryKeys = ["item_id", "tagger_id"],
    indices = [Index(value = ["tagger_id"])],
)
data class TaggedItemEntity(
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "tagger_id") val taggerId: String,
    @ColumnInfo(name = "tagged_at") val taggedAt: Long,
)
