package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ItemFieldDao {
    /**
     * Typed extras: append-only, first write wins — the `IGNORE` on the
     * `(item_id, name)` primary key is what freezes a value at ingest, exactly
     * as [ItemTagDao.insertAll] freezes a tag.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(fields: List<ItemFieldEntity>): List<Long>

    /** One item's stored extras — the evaluator's read shape. */
    @Query(Sql.ITEM_FIELDS_FOR_ITEM)
    suspend fun forItem(itemId: String): List<ItemFieldEntity>

    /**
     * Extras for a whole page at once, so rule evaluation does one batched read
     * per batch instead of one query per item. Never called with an empty list —
     * `IN ()` matches nothing in some SQLite builds and errors in others.
     */
    @Query(Sql.ITEM_FIELDS_FOR_ITEMS)
    suspend fun forItems(itemIds: List<String>): List<ItemFieldEntity>
}
