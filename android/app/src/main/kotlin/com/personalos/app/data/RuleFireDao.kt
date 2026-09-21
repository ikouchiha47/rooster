package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query

/**
 * Reads for the Watchers feed: a rule's fires and the items they fired on.
 *
 * Deliberately its own narrow interface rather than two more methods on
 * [ItemRuleDao] and [EventDao]. Those two are written by ingest and read by
 * tiles; adding feed-only queries to them would give each a second reason to
 * change, and would force every existing test fake to grow two methods it never
 * calls. Both queries are bounded by a caller-supplied `LIMIT` or by an explicit
 * id list, so neither can walk a table.
 */
@Dao
interface RuleFireDao {
    /** One rule's fires, newest first. */
    @Query(Sql.ITEM_RULES_FOR_RULE)
    suspend fun firesForRule(
        ruleId: String,
        limit: Int,
    ): List<ItemRuleEntity>

    /** The items behind a batch of fires — one read for the whole page. */
    @Query(Sql.EVENTS_BY_ULIDS)
    suspend fun eventsByUlids(ulids: List<String>): List<EventEntity>
}
