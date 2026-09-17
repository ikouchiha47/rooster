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

    /**
     * ADR 0005 T17: gauge-only replace for one observation's fields. Counter
     * paths keep using [insertAll] (`IGNORE` + freeze); calling this on a
     * counter ulid would un-freeze SMS and is forbidden.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAll(fields: List<ItemFieldEntity>)

    @Query("DELETE FROM item_fields WHERE item_id = :itemId")
    suspend fun deleteForItem(itemId: String)

    /** ADR 0005 T15: the indexed series window read. */
    @Query(Sql.SERIES_WINDOW)
    suspend fun seriesWindow(
        sourceId: String,
        field: String,
        limit: Int,
    ): List<SeriesSample>
}
