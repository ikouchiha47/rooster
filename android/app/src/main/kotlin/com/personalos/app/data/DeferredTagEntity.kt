package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An item the re-tag walk reached while its body was still [com.personalos.app.core.tag.BodyState.PENDING],
 * so it was deliberately left untagged rather than embedded on a possibly-stale
 * headline.
 *
 * The walk is a one-way cursor on `events.id` and never revisits a row, so a
 * deferred item needs somewhere to wait: this table is that place, and the re-tag
 * run drains the rows that have since become ready (body arrived, or the fetch
 * finally failed) before it sweeps.
 *
 * Keyed by `item_id` (the item's `ulid`) so a re-defer is idempotent. `row_id` is
 * carried only for ordering; the drain reads the live row through `events`.
 */
@Entity(tableName = "deferred_tags")
data class DeferredTagEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "row_id") val rowId: Long,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
)
