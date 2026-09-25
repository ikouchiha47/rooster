package com.personalos.app.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Merging newly-arrived rows into an already-loaded list, instead of throwing
 * the list away and reading page one again.
 *
 * The screen keys its rows by id, so an *insert* lets Compose reuse everything
 * already composed; clearing the list and refilling is what made the message
 * list blank and flicker on every write anywhere in the store.
 */
class MessageListMergeTest {
    private data class Row(
        val id: String,
        val timestamp: Long,
    )

    private val key: (Row) -> String = { it.id }

    /** The same ordering the query uses: newest publish first, then newest id. */
    private val newestFirst: Comparator<Row> =
        compareByDescending<Row> { it.timestamp }.thenByDescending { it.id }

    private val loaded = listOf(Row("c", 30), Row("b", 20), Row("a", 10))

    @Test
    fun `a new row joins the top without disturbing the rest`() {
        val merged =
            mergeArrivals(
                current = loaded,
                arrivals = listOf(Row("d", 40)),
                key = key,
                newestFirst = newestFirst,
                cap = 100,
            )
        assertEquals(listOf("d", "c", "b", "a"), merged.map { it.id })
    }

    @Test
    fun `a row already loaded is replaced, never duplicated`() {
        // The same key with new content: a gauge observation upserted in place,
        // or enrichment filling in body text. It must not appear twice.
        val merged =
            mergeArrivals(
                current = loaded,
                arrivals = listOf(Row("b", 20)),
                key = key,
                newestFirst = newestFirst,
                cap = 100,
            )
        assertEquals(listOf("c", "b", "a"), merged.map { it.id })
        assertEquals("no duplicate of b", 1, merged.count { it.id == "b" })
    }

    @Test
    fun `a late arrival with an older timestamp lands in order, not at the top`() {
        // A feed item can arrive now but carry an older publish time.
        val merged =
            mergeArrivals(
                current = loaded,
                arrivals = listOf(Row("x", 15)),
                key = key,
                newestFirst = newestFirst,
                cap = 100,
            )
        assertEquals(listOf("c", "b", "x", "a"), merged.map { it.id })
    }

    @Test
    fun `the cap drops the oldest, never the newest`() {
        val merged =
            mergeArrivals(
                current = loaded,
                arrivals = listOf(Row("d", 40)),
                key = key,
                newestFirst = newestFirst,
                cap = 3,
            )
        assertEquals(listOf("d", "c", "b"), merged.map { it.id })
    }

    @Test
    fun `merging nothing changes nothing`() {
        val merged = mergeArrivals(loaded, emptyList(), key, newestFirst, cap = 100)
        assertEquals(loaded, merged)
    }

    @Test
    fun `an empty load state accepts arrivals`() {
        val merged = mergeArrivals(emptyList(), listOf(Row("a", 10)), key, newestFirst, cap = 100)
        assertEquals(listOf("a"), merged.map { it.id })
    }
}
