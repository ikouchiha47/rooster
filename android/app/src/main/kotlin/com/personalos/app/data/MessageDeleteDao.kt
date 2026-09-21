package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query

/**
 * Deleting an item and everything that hangs off it.
 *
 * Its own interface rather than five more methods on [EventDao], for the same
 * reason [RuleFireDao] and [BookmarkDao] have their own: a removal concern is a
 * separate reason to change, and existing test fakes stay untouched.
 *
 * **App copy only.** These delete rows the app stored; the message on the
 * device is not touched, and nothing here talks to `content://sms`. Offering to
 * remove the device copy as well is a distinct capability (WhatsApp's "delete
 * for everyone") and is deliberately not implemented.
 */
@Dao
interface MessageDeleteDao {
    @Query(Sql.DELETE_ITEM_TAGS_BY_ITEM)
    suspend fun deleteTags(itemId: String): Int

    @Query(Sql.DELETE_MENTIONS_BY_ITEM)
    suspend fun deleteMentions(itemId: String): Int

    @Query(Sql.DELETE_ITEM_FIELDS_BY_ITEM)
    suspend fun deleteFields(itemId: String): Int

    @Query(Sql.DELETE_ITEM_RULES_BY_ITEM)
    suspend fun deleteMatches(itemId: String): Int

    @Query(Sql.DELETE_EVENT_BY_ULID)
    suspend fun deleteEvent(ulid: String): Int
}
