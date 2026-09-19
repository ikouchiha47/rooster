package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One sync run per source (ADR 0005 Events tab): when it ran, whether it
 * worked, how many items landed, and the error when it didn't.
 *
 * This is the activity log the feed status screen used to keep in memory: a
 * poll that yields zero items looks identical to one that crashed unless the
 * run itself is a row. Pruned to [SyncRecorder.RETENTION_MS], so history is
 * bounded and a silent source reads as "no recent runs" rather than stale
 * success.
 */
@Entity(
    tableName = "sync_runs",
    indices = [
        Index(value = ["source_id", "finished_at"]),
        Index(value = ["finished_at"]),
    ],
)
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `sources.id` for registry rows, `sms`, or `catalog:<feed id>`. */
    @ColumnInfo(name = "source_id") val sourceId: String,
    /** Kind string (`rss`, `search`, `sms`, `weather`, `fx`, `device`). */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long,
    @ColumnInfo(name = "ok") val ok: Boolean,
    @ColumnInfo(name = "items_added") val itemsAdded: Int,
    /** Short cause (`HTTP 403`, exception message); null on success. */
    @ColumnInfo(name = "error") val error: String?,
)
