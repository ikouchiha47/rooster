package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query

/**
 * The outbox transition: one row, keyed by the send's identity, moved from
 * `queued` to `sent`/`failed`.
 *
 * Its own interface, like [RetagDao] and [ConversationDao] — a new reason to
 * change, and [EventDao]'s test fakes stay untouched. No schema change: `type` is
 * already free-form, and the `conversations` triggers already rebalance on an
 * update of it.
 */
@Dao
interface OutboxDao {
    @Query(Sql.EVENT_UPDATE_TYPE_BY_KEY)
    suspend fun updateTypeByKey(
        key: String,
        newType: String,
    )
}
