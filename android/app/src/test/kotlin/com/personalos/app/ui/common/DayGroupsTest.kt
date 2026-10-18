package com.personalos.app.ui.common

import com.personalos.app.core.time.UserZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Day bucketing behind the sticky collapsible groups in News and Weather:
 * sequential, order-preserving, one group per bucket run.
 */
class DayGroupsTest {
    // Bucketing is the user's local day now; pin UTC so the epoch-day arithmetic
    // below is the assertion. The time-zone behaviour itself is `UserZoneTest`.
    @Before
    fun pinUtc() {
        UserZone.set("UTC")
    }

    @Test
    fun `empty list groups to nothing`() {
        assertTrue(groupIntoDays(emptyList<Long>()) { it }.isEmpty())
    }

    @Test
    fun `one bucket stays one group keyed by the bucket start`() {
        val rows = listOf(3 * DAY_MS + 100L, 3 * DAY_MS + 200L)
        val groups = groupIntoDays(rows) { it }
        assertEquals(1, groups.size)
        assertEquals(3 * DAY_MS, groups[0].dayStart)
        assertEquals(rows, groups[0].items)
    }

    @Test
    fun `a bucket change starts a new group and order is kept`() {
        val a = 5 * DAY_MS + 10L
        val b = 5 * DAY_MS + 20L
        val c = 4 * DAY_MS + 30L
        val groups = groupIntoDays(listOf(a, b, c)) { it }
        assertEquals(2, groups.size)
        assertEquals(listOf(a, b), groups[0].items)
        assertEquals(listOf(c), groups[1].items)
        assertEquals(5 * DAY_MS, groups[0].dayStart)
        assertEquals(4 * DAY_MS, groups[1].dayStart)
    }

    @Test
    fun `an item exactly on the boundary starts the new day`() {
        val groups = groupIntoDays(listOf(2 * DAY_MS, 2 * DAY_MS - 1)) { it }
        assertEquals(2, groups.size)
        assertEquals(2 * DAY_MS, groups[0].dayStart)
        assertEquals(2 * DAY_MS - 1, groups[1].items[0])
    }

    @Test
    fun `a run of sparse days collapses into one range`() {
        val rows = listOf(10 * DAY_MS + 1, 9 * DAY_MS + 1, 8 * DAY_MS + 1)
        val merged = mergeSparseDays(groupIntoDays(rows) { it }, sparseMax = 2)
        assertEquals(1, merged.size)
        assertEquals(10 * DAY_MS, merged[0].startDay)
        assertEquals(8 * DAY_MS, merged[0].endDay)
        assertTrue(merged[0].isRange)
        assertEquals(rows, merged[0].items)
    }

    @Test
    fun `a dense day is kept and splits a sparse run`() {
        val denseNew = (1..3).map { 12 * DAY_MS + it }
        val sparseA = listOf(11 * DAY_MS + 1)
        val sparseB = listOf(10 * DAY_MS + 1)
        val denseOld = (1..3).map { 9 * DAY_MS + it }
        val merged = mergeSparseDays(groupIntoDays(denseNew + sparseA + sparseB + denseOld) { it }, sparseMax = 2)
        assertEquals(3, merged.size)
        assertEquals(12 * DAY_MS, merged[0].startDay)
        assertEquals(12 * DAY_MS, merged[0].endDay)
        assertEquals(11 * DAY_MS, merged[1].startDay)
        assertEquals(10 * DAY_MS, merged[1].endDay)
        assertEquals(9 * DAY_MS, merged[2].startDay)
    }

    @Test
    fun `a single sparse day between dense days stays its own day`() {
        val denseNew = (1..3).map { 12 * DAY_MS + it }
        val sparse = listOf(11 * DAY_MS + 1)
        val denseOld = (1..3).map { 9 * DAY_MS + it }
        val merged = mergeSparseDays(groupIntoDays(denseNew + sparse + denseOld) { it }, sparseMax = 2)
        assertEquals(3, merged.size)
        assertEquals(merged[1].startDay, merged[1].endDay)
        assertFalse(merged[1].isRange)
    }

    @Test
    fun `no days merges to nothing`() {
        assertTrue(mergeSparseDays(emptyList<DayGroup<Long>>()).isEmpty())
    }

    @Test
    fun `range label spans the days and a single day delegates to dayLabel`() {
        val oldest = 8 * DAY_MS // 1970-01-09
        val newest = 29 * DAY_MS // 1970-01-30
        assertEquals(dayLabel(newest), rangeLabel(newest, newest))
        val range = rangeLabel(newest, oldest)
        assertTrue(range.contains("–"))
        assertTrue(range.contains("9"))
        assertTrue(range.contains("30"))
    }
}
