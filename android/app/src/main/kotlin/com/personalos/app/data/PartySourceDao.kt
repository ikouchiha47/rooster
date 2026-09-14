package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PartySourceDao {
    @Query(Sql.PARTY_SOURCES_ALL)
    suspend fun all(): List<PartySourceEntity>

    /** Seeded once; `IGNORE` so a re-seed never touches sync clocks. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sources: List<PartySourceEntity>)

    @Query(Sql.PARTY_SOURCES_UPDATE_SYNC)
    suspend fun updateLastSync(
        country: String,
        syncedAt: Long,
    )
}
