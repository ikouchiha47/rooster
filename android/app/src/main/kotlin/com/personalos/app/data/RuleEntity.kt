package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One v1 rule (ADR 0002): either a plain feed URL (`rss`) or a Google News
 * query (`search`). The spec is an opaque per-kind JSON string, validated at
 * write by [com.personalos.app.core.rules.RuleSpecs] — never by the schema, so
 * a future kind is a parser + adapter, never a migration.
 *
 * `seeded = 1` rows are bundled coverage and **locked, add-only**: the only
 * operation is inserting new rows. Enforcement lives in [RuleRepository] (the
 * single owner) with a `seeded = 0` guard in the DAO's own SQL, not in the UI.
 */
@Entity(tableName = "rules")
data class RuleEntity(
    /** ULID string (cf. ARCHITECTURE.md §11.1). */
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** User-visible label, e.g. `West Bengal`. Shown in the UI label path. */
    @ColumnInfo(name = "name") val name: String,
    /** `rss` | `search` — the v1 closed set (see `RuleKind`). */
    @ColumnInfo(name = "kind") val kind: String,
    /** Opaque per-kind JSON; required keys per kind, unknown keys rejected. */
    @ColumnInfo(name = "spec_json") val specJson: String,
    /** True for bundled seeds (locked); false for user rules (editable). */
    @ColumnInfo(name = "seeded") val seeded: Boolean,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    /** Epoch ms the row was written. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * Epoch ms the row was last edited (user enable/disable). Null for rows
     * written before v9; seeds and new user rules write both stamps.
     */
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
)
