package com.personalos.app.ui.climate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClimateChartTest {
    @Test
    fun `nearest index clamps and picks the closest point`() {
        assertEquals(0, nearestPointIndex(-20f, 100f, 5))
        assertEquals(2, nearestPointIndex(50f, 100f, 5))
        assertEquals(4, nearestPointIndex(120f, 100f, 5))
        assertNull(nearestPointIndex(10f, 100f, 0))
    }

    @Test
    fun `tapping the selected point clears it`() {
        assertEquals(3, togglePointSelection(null, 3))
        assertNull(togglePointSelection(3, 3))
        assertEquals(4, togglePointSelection(3, 4))
    }
}
