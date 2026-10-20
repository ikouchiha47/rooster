package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ItemEmbeddingDao {
    /** One row per item; a newer tagger replaces the vector. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<ItemEmbeddingEntity>)

    /** The stored vector for one item, or null when it has none. */
    @Query("SELECT item_id, vector FROM item_embeddings WHERE item_id = :itemId")
    suspend fun forItem(itemId: String): ItemEmbeddingRow?

    /** One read for a rule batch. */
    @Query("SELECT item_id, vector FROM item_embeddings WHERE item_id IN (:itemIds)")
    suspend fun forItems(itemIds: List<String>): List<ItemEmbeddingRow>
}

data class ItemEmbeddingRow(
    @androidx.room.ColumnInfo(name = "item_id") val itemId: String,
    @androidx.room.ColumnInfo(name = "vector") val vector: ByteArray,
)
