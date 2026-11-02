package com.personalos.app.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraTest {
    @Test
    fun `normalises longitude and clamps pitch and zoom`() {
        val camera = MapCamera(lat = 95.0, lon = 190.0, zoom = 44.0, bearing = -10.0, pitch = 80.0).normalised()

        assertTrue(camera.lat <= 85.05112878)
        assertEquals(-170.0, camera.lon, 0.0001)
        assertEquals(20.0, camera.zoom, 0.0001)
        assertEquals(350.0, camera.bearing, 0.0001)
        assertEquals(60.0, camera.pitch, 0.0001)
    }

    @Test
    fun `clamped keeps a continuous longitude during a pan`() {
        val camera = MapCamera(lat = 20.0, lon = 190.0, zoom = 2.0).clamped()

        assertEquals(190.0, camera.lon, 0.0001)
        assertEquals(20.0, camera.lat, 0.0001)
    }

    @Test
    fun `bearing wraps instead of staying negative`() {
        assertEquals(350.0, MapCamera(0.0, 0.0, 1.0, bearing = -10.0).normalised().bearing, 0.0001)
    }
}
