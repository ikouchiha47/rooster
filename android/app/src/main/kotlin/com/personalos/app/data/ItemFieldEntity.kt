package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.personalos.app.core.rules.FieldValue

/**
 * A materialised typed extra on an item (ADR 0003 §13): one row per
 * (item, name), mirroring tags and matches.
 *
 * `fields` are kind-specific and sparse — `amount`, `sender`, `doi`, `price` —
 * so a JSON blob on `events` was the tempting shape and is wrong here: §8's
 * monitors need a series key the query planner can use, and a JSON column
 * cannot be indexed by key. The two indices are exactly that: `(name, item_id)`
 * for a window read, `(item_id)` for reading one item's extras back.
 *
 * The three value columns mirror the language's [FieldValue] (`Num` / `Str` /
 * `Flag`) exactly, so neither side widens or parses. Exactly one is set;
 * [ItemFieldEntity.toFieldValue] is the single place that mapping lives.
 *
 * Rows are append-only with `IGNORE` on the natural key, so a repeat ingest
 * never rewrites a value — fields are **frozen at ingest** (ADR §3).
 */
@Entity(
    tableName = "item_fields",
    primaryKeys = ["item_id", "name"],
    indices = [Index(value = ["name", "item_id"]), Index(value = ["item_id"])],
)
data class ItemFieldEntity(
    /** `events.ulid` */
    @ColumnInfo(name = "item_id") val itemId: String,
    /** `amount`, `sender`, `doi`, `price`, ... */
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "value_num") val valueNum: Double? = null,
    @ColumnInfo(name = "value_text") val valueText: String? = null,
    @ColumnInfo(name = "value_flag") val valueFlag: Boolean? = null,
)

/**
 * The stored row as the evaluator's [FieldValue], or null when no value column
 * is set (an impossible row, but a read must not throw on one).
 */
fun ItemFieldEntity.toFieldValue(): FieldValue? =
    when {
        valueNum != null -> FieldValue.Num(valueNum)
        valueText != null -> FieldValue.Str(valueText)
        valueFlag != null -> FieldValue.Flag(valueFlag)
        else -> null
    }

/** The row for one typed value, so no writer picks a column by hand. */
fun FieldValue.toEntity(
    itemId: String,
    name: String,
): ItemFieldEntity =
    when (this) {
        is FieldValue.Num -> ItemFieldEntity(itemId = itemId, name = name, valueNum = value)
        is FieldValue.Str -> ItemFieldEntity(itemId = itemId, name = name, valueText = value)
        is FieldValue.Flag -> ItemFieldEntity(itemId = itemId, name = name, valueFlag = value)
    }
