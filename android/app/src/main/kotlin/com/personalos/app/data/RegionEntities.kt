package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved map camera. Global: no service owns a private copy. */
@Entity(
    tableName = "saved_regions",
    indices = [Index(value = ["source_kind", "source_id"], unique = true)],
)
data class SavedRegionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "zoom") val zoom: Double,
    @ColumnInfo(name = "bearing") val bearing: Double = 0.0,
    @ColumnInfo(name = "pitch") val pitch: Double = 0.0,
    @ColumnInfo(name = "source_kind") val sourceKind: String? = null,
    @ColumnInfo(name = "source_id") val sourceId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** The region a consumer starts at. Home is a region default, not a flag on a row. */
@Entity(tableName = "region_defaults")
data class RegionDefaultEntity(
    @PrimaryKey @ColumnInfo(name = "consumer") val consumer: String,
    @ColumnInfo(name = "region_id") val regionId: String?,
)
