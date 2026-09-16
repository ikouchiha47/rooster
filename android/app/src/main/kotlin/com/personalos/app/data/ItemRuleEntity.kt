package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A materialised rule match (ADR 0003 §13): append-only, one row per item and
 * rule. Modelled on [ItemTagEntity] — inserts are `IGNORE`, so re-evaluation
 * never duplicates a match and the first write wins.
 */
@Entity(
    tableName = "item_rules",
    primaryKeys = ["item_id", "rule_id"],
    indices = [Index(value = ["rule_id", "item_id"]), Index(value = ["item_id"])],
)
data class ItemRuleEntity(
    /** `events.ulid` */
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "matched_at") val matchedAt: Long,
)
