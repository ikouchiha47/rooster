package com.personalos.app.data

import com.personalos.app.core.search.SmsSearch
import com.personalos.app.core.search.smsSearch

/**
 * Messages search: the one read path behind the search field.
 *
 * The plan — MATCH, LIKE or nothing — is decided in
 * [com.personalos.app.core.search] and tested there; this only runs the chosen
 * read. Both reads are scoped to SMS in SQL, exactly like every other Messages
 * query, so search can never surface a feed item; there is deliberately no
 * global search.
 *
 * The caller supplies `limit` because bounding a result set is a display
 * decision, not the store's (docs/CODE-DESIGN-GUIDELINES.md §1).
 */
class MessageSearchRepository(
    private val dao: MessageSearchDao,
) {
    /** Rows matching [raw], best textual match first. Empty for a blank query. */
    suspend fun search(
        raw: String,
        limit: Int,
    ): List<EventEntity> =
        when (val plan = smsSearch(raw)) {
            SmsSearch.Blank -> emptyList()
            is SmsSearch.Match -> dao.searchMatch(plan.expression, limit)
            is SmsSearch.Like -> dao.searchLike(plan.pattern, limit)
        }
}
