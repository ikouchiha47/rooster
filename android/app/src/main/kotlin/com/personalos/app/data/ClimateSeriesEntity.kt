package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** One stored point in a climate series, keyed by source + metric + region + period. */
@Entity(
    tableName = "climate_series",
    primaryKeys = ["source_id", "metric", "region", "period_start"],
    indices = [Index(value = ["metric", "region", "period_start"])],
)
data class ClimateSeriesEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "metric") val metric: String,
    @ColumnInfo(name = "region") val region: String,
    @ColumnInfo(name = "period_start") val periodStart: Long,
    @ColumnInfo(name = "period_end") val periodEnd: Long,
    @ColumnInfo(name = "value") val value: Double,
    @ColumnInfo(name = "anomaly") val anomaly: Double?,
    @ColumnInfo(name = "unit") val unit: String,
    @ColumnInfo(name = "baseline") val baseline: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
