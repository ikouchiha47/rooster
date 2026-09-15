package com.personalos.app.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RadarThemeTest {
    @Test
    fun `exactly two themes exist`() {
        assertEquals(
            listOf(RadarTheme.IBM_PLEX, RadarTheme.SPACE_GROTESK),
            RadarTheme.entries.toList(),
        )
    }

    @Test
    fun `ids are the stored keys`() {
        assertEquals("ibm_plex", RadarTheme.IBM_PLEX.id)
        assertEquals("space_grotesk", RadarTheme.SPACE_GROTESK.id)
    }

    @Test
    fun `fromId resolves both ids`() {
        assertSame(RadarTheme.IBM_PLEX, RadarTheme.fromId("ibm_plex"))
        assertSame(RadarTheme.SPACE_GROTESK, RadarTheme.fromId("space_grotesk"))
    }

    @Test
    fun `fromId falls back to the default for missing or unknown ids`() {
        assertSame(RadarTheme.Default, RadarTheme.fromId(null))
        assertSame(RadarTheme.Default, RadarTheme.fromId("noto"))
        assertSame(RadarTheme.Default, RadarTheme.fromId(""))
    }

    @Test
    fun `the default is ibm_plex`() {
        assertSame(RadarTheme.IBM_PLEX, RadarTheme.Default)
    }
}
