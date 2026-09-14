package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The party registry: one row per party the extractor can name.
 *
 * Seeded from [com.personalos.app.core.mention.BundledPartySource] on first
 * launch, kept afterwards by the party sync (which upserts by slug and refreshes
 * `updated_at`). New slugs insert freely — that is how an emerging party
 * arrives without a release. A slug missing from a sync keeps its old
 * `updated_at`, which is what marks it stale rather than deleting it: parties
 * merge and split, and a name vanishing from one snapshot is not proof it is
 * gone.
 */
@Entity(
    tableName = "parties",
    indices = [Index(value = ["country"])],
)
data class PartyEntity(
    /** Stable id; also the `entity_id` on party mention rows. */
    @PrimaryKey @ColumnInfo(name = "slug") val slug: String,
    @ColumnInfo(name = "country") val country: String,
    @ColumnInfo(name = "name") val name: String,
    /** Matchable surfaces, pipe-joined. */
    @ColumnInfo(name = "aliases") val aliases: String,
    /** Associated state, or empty when the source states none. */
    @ColumnInfo(name = "stronghold") val stronghold: String,
    /** The tier that earned the row (`national`, `duma`, `major`, ...). */
    @ColumnInfo(name = "recognition") val recognition: String,
    /** Epoch ms of the last seed-or-sync that wrote this row. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Where each country's party list syncs from, and when it last did.
 *
 * URLs live in [com.personalos.app.core.mention.PartySourceUrls]; this table
 * holds the copy the sync actually reads plus its per-country clock, so a URL
 * change ships with the seed and the schedule survives it.
 */
@Entity(tableName = "party_sources")
data class PartySourceEntity(
    @PrimaryKey @ColumnInfo(name = "country") val country: String,
    @ColumnInfo(name = "url") val url: String,
    /** Epoch ms of the last completed sync, null when never synced. */
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long?,
)
