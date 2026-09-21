package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * ADR 0005 T6: producer descriptors as data.
 *
 * `kinds` names what the app can ingest, `facets` names what rules can match,
 * `kind_facets` joins them. Bundled rows are add-only (`seeded = 1`); a later
 * release inserts rows, never rewrites stored specs. Column order and
 * affinities mirror `MIGRATION_14_15_STATEMENTS` — Room validates a migrated
 * database against them on open.
 */
@Entity(tableName = "kinds", primaryKeys = ["id"])
data class KindEntity(
    @ColumnInfo(name = "id") val id: String,
    /** `item` | `series` — the write axis ([IngestGrain]). */
    @ColumnInfo(name = "mode") val mode: String,
    /** Pipe-joined topics, e.g. `news|weather`. */
    @ColumnInfo(name = "topics") val topics: String,
    @ColumnInfo(name = "enrichable") val enrichable: Boolean,
    @ColumnInfo(name = "seeded") val seeded: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "facets", primaryKeys = ["id"])
data class FacetEntity(
    @ColumnInfo(name = "id") val id: String,
    /** `num` | `str` | `enum` | `flag`. */
    @ColumnInfo(name = "value_type") val valueType: String,
    /** `counter` | `gauge` | `histogram`. */
    @ColumnInfo(name = "measure") val measure: String,
    /** Comma-joined op tokens. */
    @ColumnInfo(name = "ops") val ops: String,
    /** Pipe-joined enum values, null when not an enum facet. */
    @ColumnInfo(name = "values_from") val valuesFrom: String?,
    @ColumnInfo(name = "seeded") val seeded: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "kind_facets", primaryKeys = ["kind_id", "facet_id"])
data class KindFacetEntity(
    @ColumnInfo(name = "kind_id") val kindId: String,
    @ColumnInfo(name = "facet_id") val facetId: String,
)
