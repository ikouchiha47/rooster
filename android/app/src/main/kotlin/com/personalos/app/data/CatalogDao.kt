package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KindDao {
    @Query(Sql.KINDS_ALL)
    fun observeAll(): Flow<List<KindEntity>>

    @Query(Sql.KINDS_ALL)
    suspend fun all(): List<KindEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(kinds: List<KindEntity>): List<Long>
}

@Dao
interface FacetDao {
    @Query(Sql.FACETS_ALL)
    fun observeAll(): Flow<List<FacetEntity>>

    @Query(Sql.FACETS_ALL)
    suspend fun all(): List<FacetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(facets: List<FacetEntity>): List<Long>
}

@Dao
interface KindFacetDao {
    @Query(Sql.KIND_FACETS_ALL)
    suspend fun all(): List<KindFacetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<KindFacetEntity>): List<Long>
}
