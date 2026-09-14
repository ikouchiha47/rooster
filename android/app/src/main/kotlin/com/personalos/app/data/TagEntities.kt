package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personalos.app.core.tag.Tagger

/**
 * Registry of tagger implementations. One row per implementation *and* version,
 * so `heuristic-v1` and `heuristic-v2` coexist and their output can be compared
 * on the same corpus. See docs/ARCHITECTURE.md §11.3.
 */
@Entity(tableName = "taggers")
data class TaggerEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "active") val active: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Append-only tag rows. Re-tagging inserts; it never overwrites, so provenance
 * survives, a bad tagger can be rolled back, and the previous tag set stays
 * readable while a new one rolls out.
 */
@Entity(
    tableName = "item_tags",
    primaryKeys = ["item_id", "tag", "tagger_id"],
    indices = [Index(value = ["tag", "item_id"]), Index(value = ["item_id"])],
)
data class ItemTagEntity(
    /** `events.ulid` */
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "tag") val tag: String,
    @ColumnInfo(name = "tagger_id") val taggerId: String,
    @ColumnInfo(name = "confidence") val confidence: Float = 1f,
    @ColumnInfo(name = "entity") val entity: String? = null,
    @ColumnInfo(name = "tagged_at") val taggedAt: Long,
)

fun Tagger.toEntity(now: Long): TaggerEntity =
    TaggerEntity(
        id = id,
        kind = kind.name,
        version = version,
        active = true,
        createdAt = now,
    )
