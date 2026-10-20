package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TaggedItemDao {
    /** One marker per (item, tagger); re-tagging the same item is idempotent. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markAll(rows: List<TaggedItemEntity>): List<Long>

    /** How many items this tagger has processed — the retag walk's real progress. */
    @Query("SELECT COUNT(*) FROM tagged_items WHERE tagger_id = :taggerId")
    suspend fun countForTagger(taggerId: String): Int
}
