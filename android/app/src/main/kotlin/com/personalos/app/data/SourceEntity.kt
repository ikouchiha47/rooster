package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One source (ADR 0003): either a plain feed URL (`rss`) or a Google News
 * query (`search`). The spec is an opaque per-kind JSON string, validated at
 * write by [com.personalos.app.core.sources.SourceSpecs] — never by the schema, so
 * a future kind is a parser + adapter, never a migration.
 *
 * `seeded = 1` rows are bundled coverage and **locked, add-only**: the only
 * operation is inserting new rows. Enforcement lives in [SourceRepository] (the
 * single owner) with a `seeded = 0` guard in the DAO's own SQL, not in the UI.
 */
@Entity(tableName = "sources")
data class SourceEntity(
    /** ULID string (cf. ARCHITECTURE.md §11.1). */
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** User-visible label, e.g. `West Bengal`. Shown in the UI label path. */
    @ColumnInfo(name = "name") val name: String,
    /** `rss` | `search` — the v1 closed set (see `SourceKind`). */
    @ColumnInfo(name = "kind") val kind: String,
    /** Opaque per-kind JSON; required keys per kind, unknown keys rejected. */
    @ColumnInfo(name = "spec_json") val specJson: String,
    /** True for bundled seeds (locked); false for user sources (editable). */
    @ColumnInfo(name = "seeded") val seeded: Boolean,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    /** Epoch ms the row was written. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * Epoch ms the row was last edited (user enable/disable). Null for rows
     * written before v9; seeds and new user sources write both stamps.
     */
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    /**
     * Re-poll interval in seconds. Null follows the shared feed schedule; user
     * rows default to it at insert (the same cadence the catalog feeds use).
     * Seeds stay null — the catalog pass already paces those URLs.
     */
    @ColumnInfo(name = "interval_sec") val intervalSec: Long? = null,
)
