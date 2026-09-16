package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemTagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<ItemTagEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun register(tagger: TaggerEntity)

    @Query(Sql.TAGGER_DEACTIVATE_ALL)
    suspend fun deactivateAll()

    @Query(Sql.TAGGER_ACTIVE)
    suspend fun activeTagger(): TaggerEntity?

    @Query(Sql.ITEM_TAGS_COUNT_FOR_TAGGER)
    suspend fun countForTagger(taggerId: String): Int

    /** Per-tag item counts, for the Sources screen. */
    @Query(Sql.ITEM_TAGS_COUNT_BY_TAG)
    fun observeTagCounts(): Flow<List<TagCount>>

    /** Items carrying a tag, newest first - the read shape tiles use. */
    @Query(Sql.ITEM_TAGS_FOR_TAG)
    suspend fun itemsForTag(
        tag: String,
        taggerId: String,
        limit: Int,
    ): List<String>

    @Query(Sql.ITEM_TAGS_DELETE_ALL)
    suspend fun deleteAll()

    /** The active tagger's tags for a page of items — one read, not one per row. */
    @Query(Sql.ITEM_TAGS_CURRENT_FOR_ITEMS)
    suspend fun tagsForItems(itemIds: List<String>): List<ItemTagRow>
}
