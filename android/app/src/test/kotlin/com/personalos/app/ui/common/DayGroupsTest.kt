package com.personalos.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Day bucketing behind the sticky collapsible groups in News and Weather:
 * sequential, order-preserving, one group per bucket run.
 */
class DayGroupsTest {
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
}
