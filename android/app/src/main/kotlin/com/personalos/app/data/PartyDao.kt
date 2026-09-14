package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PartyDao {
    @Query(Sql.PARTIES_COUNT)
    suspend fun count(): Int

    @Query(Sql.PARTIES_ALL)
    suspend fun all(): List<PartyEntity>

    /**
     * Insert-or-update by slug. The sync path depends on this: re-seen parties
     * refresh `updated_at`, new slugs arrive, and nothing is ever deleted by a
     * sync — a slug missing from one snapshot keeps its old timestamp, which is
     * what marks it stale.
     */
    @Upsert
    suspend fun upsertAll(parties: List<PartyEntity>)
}
