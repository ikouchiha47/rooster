package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A writable calendar container. `Personal` is seeded as the default; imported
 * and device calendars are the same shape, so a reminder can name its target
 * without knowing which kind of calendar it is.
 */
@Entity(tableName = "planner_workspaces")
data class PlannerWorkspaceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    /** `personal` | `custom` | `imported` | `device`. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    /** For provider-backed workspaces; null for Personal/Custom. */
    @ColumnInfo(name = "source_id") val sourceId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One user-owned event or reminder. Source items are facts elsewhere; this is
 * the decision the user made from them, so it carries provenance but owns its
 * own title, times and recurrence.
 */
@Entity(
    tableName = "planner_items",
    indices = [
        Index(value = ["workspace_id", "start_at"]),
        Index(value = ["start_at"]),
        Index(value = ["source_item_id"]),
    ],
)
data class PlannerItemEntity(
    /** ULID. */
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
    /** `event` | `reminder`. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "notes") val notes: String = "",
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long? = null,
    @ColumnInfo(name = "all_day") val allDay: Boolean = false,
    @ColumnInfo(name = "time_zone") val timeZone: String,
    /** RFC 5545 subset, e.g. `FREQ=YEARLY`; null for one-off. */
    @ColumnInfo(name = "recurrence_rule") val recurrenceRule: String? = null,
    @ColumnInfo(name = "recurrence_until") val recurrenceUntil: Long? = null,
    @ColumnInfo(name = "recurrence_count") val recurrenceCount: Int? = null,
    /** `scheduled` | `done` | `dismissed` | `snoozed`. */
    @ColumnInfo(name = "status") val status: String = "scheduled",
    /** How early a reminder notifies; null for an event with no reminder. */
    @ColumnInfo(name = "lead_minutes") val leadMinutes: Int? = null,
    /** `events.ulid` provenance, when the item came from a source. */
    @ColumnInfo(name = "source_item_id") val sourceItemId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** A user-selectable reminder/event label; rows are data, not an enum. */
@Entity(tableName = "planner_tags")
data class PlannerTagEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label") val label: String,
    /** Palette hex, chosen by the seed; UI decides how loud to make it. */
    @ColumnInfo(name = "color") val color: String,
    @ColumnInfo(name = "sort") val sort: Int,
)

/** Many tags per item; the label itself lives in [PlannerTagEntity]. */
@Entity(
    tableName = "planner_item_tags",
    primaryKeys = ["item_id", "tag_id"],
    indices = [Index(value = ["tag_id"])],
)
data class PlannerItemTagEntity(
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
)
