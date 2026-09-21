package com.personalos.app.ui.messages

import com.personalos.app.core.Chars
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two lines the search results area shows, tested without rendering. The
 * search's behaviour is tested in `core/search`; this pins only the wording, and
 * that the query is echoed so a stale result set cannot be mistaken for another.
 */
class MessagesSearchModelTest {
    @Test
    fun `the summary states the count and the query`() {
        assertEquals("3 RESULTS ${Chars.MIDDLE_DOT} airtel", messagesSearchSummary("airtel", 3))
    }

    @Test
    fun `a count of zero is still stated, not omitted`() {
        assertEquals("0 RESULTS ${Chars.MIDDLE_DOT} zzz", messagesSearchSummary("zzz", 0))
    }

    @Test
    fun `the empty message names the query that found nothing`() {
        assertEquals("NO MESSAGES MATCH 9876543210", messagesSearchEmpty("9876543210"))
    }
}
