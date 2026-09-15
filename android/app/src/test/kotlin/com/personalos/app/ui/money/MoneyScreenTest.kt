package com.personalos.app.ui.money

import com.personalos.app.core.model.FxRate
import com.personalos.app.data.EventEntity
import com.personalos.app.data.TaggedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M&M tile's UI-side decisions: rate figures and the in-memory search over
 * the loaded finance page. Both live in the UI (caps and formatting belong
 * there); both are pure, so they are tested without composing.
 */
class MoneyScreenTest {
    private fun item(
        id: Long,
        title: String,
    ) = TaggedEvent(
        event =
            EventEntity(
                id = id,
                ulid = "ulid-$id",
                dedupeKey = "key-$id",
                source = "rss:mint-markets",
                type = "rss",
                timestamp = id,
                title = title,
                content = "",
            ),
        tags = "news,finance",
    )

    private fun rate(pair: String) = FxRate(id = pair.lowercase(), pair = pair, rate = 95.564, note = "ECB")

    @Test
    fun `rates read as two decimals`() {
        assertEquals("95.56", formatFxRate(rate("USD/INR")))
    }

    @Test
    fun `blank query keeps the whole page`() {
        val page = listOf(item(1, "Sensex rallies"), item(2, "Bank holiday"))
        assertEquals(page, filterFinanceNews(page, ""))
        assertEquals(page, filterFinanceNews(page, "   "))
    }

    @Test
    fun `query matches headlines case-insensitively`() {
        val page = listOf(item(1, "Sensex rallies"), item(2, "Bank holiday"))
        assertEquals(listOf(page[1]), filterFinanceNews(page, "bank"))
        assertEquals(listOf(page[0]), filterFinanceNews(page, "SENSEX"))
    }

    @Test
    fun `no match reads empty rather than throwing`() {
        val page = listOf(item(1, "Sensex rallies"))
        assertTrue(filterFinanceNews(page, "cricket").isEmpty())
    }
}
