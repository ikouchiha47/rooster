package com.personalos.app.ui.messages

import com.personalos.app.core.Chars

// Messages search's display decisions, kept out of the composable so they can
// be tested without rendering - the same split as [MessagesTab] and
// `WatchersModel`. The composable lays these out; it does not decide what they
// say. The search itself (what counts as a query, and how it reaches the store)
// lives in `core/search`; these are only the two strings the results area needs.

/** The line above a result set: count first, then what was searched for. */
fun messagesSearchSummary(
    query: String,
    count: Int,
): String = "$count RESULTS ${Chars.MIDDLE_DOT} $query"

/** What an empty result set says, naming the query so the scope is never a mystery. */
fun messagesSearchEmpty(query: String): String = "NO MESSAGES MATCH $query"
