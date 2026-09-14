package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.DatabaseView

/**
 * Read-only projection over `item_tags` limited to the active tagger.
 *
 * Because tag rows are append-only, the underlying table accumulates output
 * from every tagger version side by side (that is what makes a rollback or a
 * version comparison possible). Anything that *reads* tags must therefore go
 * through this view, or each item would appear once per tagger version.
 */
@DatabaseView(viewName = "item_tags_current", value = Sql.ITEM_TAGS_CURRENT)
data class ItemTagCurrent(
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "tag") val tag: String,
    @ColumnInfo(name = "tagger_id") val taggerId: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "entity") val entity: String?,
    @ColumnInfo(name = "tagged_at") val taggedAt: Long,
)
