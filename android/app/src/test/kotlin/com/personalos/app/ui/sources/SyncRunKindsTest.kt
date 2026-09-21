package com.personalos.app.ui.sources

import com.personalos.app.data.SyncRunEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRunKindsTest {
    private fun run(
        id: Long,
        sourceId: String,
        kind: String,
        finishedAt: Long,
        ok: Boolean = true,
    ) = SyncRunEntity(
        id = id,
        sourceId = sourceId,
        kind = kind,
        startedAt = finishedAt - 10,
        finishedAt = finishedAt,
        ok = ok,
        itemsAdded = 1,
        error = null,
    )

    @Test
    fun `one row per kind, newest run wins`() {
        val runs =
            listOf(
                run(1, "rss:a", "rss", 100),
                run(2, "rss:b", "rss", 300),
                run(3, "sms", "sms", 50),
            )
        val kinds = syncRunKinds(runs)
        assertEquals(listOf("rss", "sms"), kinds.map { it.kind })
        assertEquals(300L, kinds.first { it.kind == "rss" }.finishedAt)
    }

    @Test
    fun `kinds read in service order, unknown kinds trail`() {
        val runs =
            listOf(
                run(1, "fx:usd-inr", "fx", 10),
                run(2, "sms", "sms", 20),
                run(3, "imd:bengaluru", "imd", 5),
            )
        assertEquals(listOf("sms", "fx", "imd"), syncRunKinds(runs).map { it.kind })
    }

    @Test
    fun `kind labels are the service names`() {
        assertEquals("Feeds", syncKindLabel("rss"))
        assertEquals("Topics", syncKindLabel("search"))
        assertEquals("SMS", syncKindLabel("sms"))
        assertEquals("Weather", syncKindLabel("weather"))
        assertEquals("FX", syncKindLabel("fx"))
        assertEquals("Device", syncKindLabel("device"))
        // An unknown future kind is named honestly rather than hidden.
        assertEquals("imd", syncKindLabel("imd"))
    }
}
