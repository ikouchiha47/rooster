package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of the bundled India gazetteer (`places.dat`, built by
 * `tools/build_places.py`).
 *
 * Column order mirrors the asset: geonameid, display name, ascii name, lat,
 * lon, feature class, feature code, admin1, admin2, admin3, population,
 * pipe-joined alternates. Only admin1 is stored — admin2/3 are district
 * detail no matcher reads.
 */
@Entity(tableName = "places")
data class PlaceEntity(
    /** GeoNames geonameid. */
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "ascii") val ascii: String,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "fclass") val fclass: String,
    @ColumnInfo(name = "fcode") val fcode: String,
    /** State admin1 code, e.g. `28` for West Bengal. */
    @ColumnInfo(name = "admin1") val admin1: String,
    @ColumnInfo(name = "population") val population: Long,
    /** Alternate names, pipe-joined exactly as stored. */
    @ColumnInfo(name = "alternates") val alternates: String,
)

/**
 * A place or party spotted in an item's text. Stored, not yet displayed.
 *
 * `kind` is free TEXT (`place`, `party`, ...) rather than an enum table, so a
 * future kind needs no migration. Rows are append-only with `IGNORE` on the
 * natural key, so re-runs never duplicate.
 */
@Entity(
    tableName = "mentions",
    primaryKeys = ["item_id", "kind", "surface"],
    indices = [Index(value = ["item_id"]), Index(value = ["kind", "surface"])],
)
data class MentionEntity(
    /** `events.ulid` */
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "kind") val kind: String,
    /** The gazetteer/lexicon surface as stored, not the text's casing. */
    @ColumnInfo(name = "surface") val surface: String,
    /** Gazetteer geonameid for places, slug for parties, null when unlinked. */
    @ColumnInfo(name = "entity_id") val entityId: String?,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "mentioned_at") val mentionedAt: Long,
)
