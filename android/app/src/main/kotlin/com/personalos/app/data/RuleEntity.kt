package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A rule (ADR 0003): a named condition and action, stored as opaque JSON.
 *
 * Storage only in this slice — nothing evaluates a rule yet, so `condition_json`
 * and `action_json` are carried verbatim. The predicate language, its
 * validation and the evaluator arrive without a schema change.
 *
 * `seeded = 1` rows are intended to be bundled and locked, add-only, mirroring
 * [SourceEntity] seeds. That is intent, not fact yet — no seeder, write guard or
 * test exists. `position` is ordering-for-surfacing, not a fetch interval.
 */
@Entity(tableName = "rules")
data class RuleEntity(
    /** ULID string (cf. ARCHITECTURE.md §11.1). */
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** User-visible label. */
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    /** True for bundled seeds (locked); false for user rules (editable). */
    @ColumnInfo(name = "seeded") val seeded: Boolean,
    /** Opaque condition JSON; the predicate language lands in slice 2. */
    @ColumnInfo(name = "condition_json") val conditionJson: String,
    /** Opaque action JSON; delivery semantics land later. */
    @ColumnInfo(name = "action_json") val actionJson: String,
    @ColumnInfo(name = "position") val position: Long,
    /** Epoch ms the row was written. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
)
