package com.personalos.app.ui.news

import com.personalos.app.data.DayHeader
import com.personalos.app.data.EventEntity
import com.personalos.app.data.TaggedEvent
import com.personalos.app.data.work.SyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure News section helpers: sync buckets group Today by ingest run, the pill
 * counts drift above the last seen head, and sections wire headers to pages.
 */
class NewsSectionsTest {
    @Test
    fun `the bucket width is the single sync-schedule value`() {
        assertEquals(SyncScheduler.DEFAULT_INTERVAL_MINUTES, SyncScheduler.SYNC_GROUP_MINUTES)
    }

    @Test
    fun `today starts on the day boundary`() {
        assertEquals(3 * DAY_MS, todayStartMs(3 * DAY_MS + 123L))
        assertEquals(3 * DAY_MS, todayStartMs(3 * DAY_MS))
    }

    @Test
    fun `the bucket read falls back to publish time`() {
        assertEquals(9L, effectiveIngestedAt(9L, 1L))
        assertEquals(1L, effectiveIngestedAt(null, 1L))
    }

    @Test
    fun `a bucket starts on the group boundary`() {
        val hour = 60L
        assertEquals(5 * 3_600_000L, syncBucketStart(5 * 3_600_000L + 1L, hour))
        assertEquals(5 * 3_600_000L, syncBucketStart(5 * 3_600_000L, hour))
    }

    @Test
    fun `buckets group by ingest run and keep order`() {
        val rows = listOf(row(3L, ingest = 90_000L), row(2L, ingest = 80_000L), row(1L, ingest = 10_000L))
        val buckets = groupIntoSyncBuckets(rows, { it.event.ingestedAt }, { it.event.timestamp }, groupMinutes = 1L)
        assertEquals(2, buckets.size)
        assertEquals(listOf(3L, 2L), buckets[0].items.map { it.event.id })
        assertEquals(listOf(1L), buckets[1].items.map { it.event.id })
    }

    @Test
    fun `a missing ingest time buckets on publish time`() {
        val rows = listOf(row(2L, ingest = null, published = 61_000L), row(1L, ingest = null, published = 1_000L))
        val buckets = groupIntoSyncBuckets(rows, { it.event.ingestedAt }, { it.event.timestamp }, groupMinutes = 1L)
        assertEquals(2, buckets.size)
    }

    @Test
    fun `empty rows bucket to nothing`() {
        assertTrue(groupIntoSyncBuckets(emptyList<TaggedEvent>(), { null }, { 0L }).isEmpty())
    }

    @Test
    fun `the pill counts what sits above the seen head`() {
        assertEquals(0, freshCountAboveHead(listOf(3L, 2L, 1L), null))
        assertEquals(0, freshCountAboveHead(listOf(3L, 2L, 1L), 3L))
        assertEquals(2, freshCountAboveHead(listOf(5L, 4L, 3L), 3L))
        assertEquals(3, freshCountAboveHead(listOf(5L, 4L, 3L), 9L))
        assertEquals(0, freshCountAboveHead(emptyList(), 3L))
    }

    @Test
    fun `sections keep header order and scope buckets to today`() {
        val today = 10 * DAY_MS
        val headers = listOf(DayHeader(today, 2), DayHeader(today - DAY_MS, 1))
        val items =
            mapOf(
                today to listOf(row(2L, ingest = today + 10L), row(1L, ingest = today + 20L)),
                today - DAY_MS to listOf(row(0L, ingest = today - DAY_MS + 10L)),
            )
        val sections = buildDaySections(headers, items, today, "")
        assertEquals(listOf(today, today - DAY_MS), sections.map { it.dayStart })
        assertEquals(listOf(2, 1), sections.map { it.total })
        assertEquals(1, sections[0].buckets?.size)
        assertNull(sections[1].buckets)
    }

    @Test
    fun `sections filter on the query and report filtered counts`() {
        val today = 10 * DAY_MS
        val headers = listOf(DayHeader(today, 2))
        val items = mapOf(today to listOf(row(2L, title = "Metro opens"), row(1L, title = "Match report")))
        val sections = buildDaySections(headers, items, today, "metro")
        assertEquals(1, sections.single().rows.size)
        assertEquals(1, sections.single().total)
    }

    private fun row(
        id: Long,
        ingest: Long? = null,
        published: Long = id,
        title: String = "t$id",
    ): TaggedEvent =
        TaggedEvent(
            EventEntity(
                id = id,
                ulid = "u$id",
                dedupeKey = "k$id",
                source = "rss:x",
                type = "feed",
                timestamp = published,
                title = title,
                content = "body",
                ingestedAt = ingest,
            ),
            "news",
        )

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
