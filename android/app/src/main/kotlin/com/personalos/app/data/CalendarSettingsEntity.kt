package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** One surface's default namespace, or null for "all". */
@Entity(tableName = "calendar_settings")
data class CalendarSettingsEntity(
    @PrimaryKey @ColumnInfo(name = "surface") val surface: String,
    @ColumnInfo(name = "default_source_id") val defaultSourceId: String?,
)

/**
 * One explicit visibility row for a surface. No row means "use the default";
 * after the user toggles a namespace, the rows are the saved set.
 */
@Entity(tableName = "calendar_selections", primaryKeys = ["surface", "source_id"])
data class CalendarSelectionEntity(
    @ColumnInfo(name = "surface") val surface: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
)
