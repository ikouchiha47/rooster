package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    /** The repository's read surface: one `Flow<List<SourceEntity>>` (ADR 0003). */
    @Query(Sql.SOURCES_ALL)
    fun observeAll(): Flow<List<SourceEntity>>

    @Query(Sql.SOURCES_ALL)
    suspend fun all(): List<SourceEntity>

    /** Enabled rows of any kind — what the ingestor polls. */
    @Query(Sql.SOURCES_ENABLED)
    suspend fun enabled(): List<SourceEntity>

    @Query(Sql.SOURCES_COUNT)
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sources: List<SourceEntity>): List<Long>

    /**
     * Guarded: `seeded` rows never match, so enabling/disabling a locked seed
     * is a no-op here and an error in [SourceRepository]. Returns rows touched.
     * Every edit stamps `updated_at`, so the row says when it last changed.
     */
    @Query(Sql.SOURCES_UPDATE_ENABLED)
    suspend fun updateEnabled(
        id: String,
        enabled: Boolean,
        updatedAt: Long,
    ): Int

    /**
     * Guarded: only user rows delete. Returns rows removed.
     */
    @Query(Sql.SOURCES_DELETE_USER_ONLY)
    suspend fun deleteUserOnly(id: String): Int
}
