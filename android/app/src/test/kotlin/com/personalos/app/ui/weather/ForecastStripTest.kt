package com.personalos.app.ui.weather

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.personalos.app.core.model.WeatherDay
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.theme.CategoryColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip's condition wash: one quiet tint per band, pale ink only on the
 * dark thunder band, and no tint at all without data.
 */
class ForecastStripTest {
    private fun day(rainChance: Int) =
        WeatherDay(
            date = "2026-09-14",
            maxC = 31,
            minC = 24,
            rainChance = rainChance,
        )

    /** WCAG contrast of two opaque colours. */
    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `each band maps to its own tint`() {
        // Code-first bands share their rain-band wash: fog and snow read as
        // cloud, drizzle and showers as rain. The grouping is explicit — a fog
        // day must tint exactly like a cloudy one, never invent a new colour.
        assertEquals(conditionTint(WeatherCondition.Cloudy), conditionTint(WeatherCondition.Fog))
        assertEquals(conditionTint(WeatherCondition.Cloudy), conditionTint(WeatherCondition.Snow))
        assertEquals(conditionTint(WeatherCondition.Rain), conditionTint(WeatherCondition.Drizzle))
        assertEquals(conditionTint(WeatherCondition.Rain), conditionTint(WeatherCondition.Showers))
        val tints = WeatherCondition.entries.map(::conditionTint)
        assertEquals(5, tints.toSet().size)
    }

    @Test
    fun `thunder stays solid indigo from the palette`() {
        assertEquals(CategoryColors.Indigo, conditionTint(WeatherCondition.Storm))
    }

    @Test
    fun `light tints keep ink text readable`() {
        val light =
            listOf(
                WeatherCondition.Clear,
                WeatherCondition.PartlyCloudy,
                WeatherCondition.Cloudy,
                WeatherCondition.Rain,
            ).map(::conditionTint)
        light.forEach { tint ->
            assertTrue(tint.luminance() > 0.6)
            assertTrue(contrast(RadarColors.ink, tint) >= 4.5)
        }
    }

    @Test
    fun `pale paper text reads on the thunder tint`() {
        val storm = conditionTint(WeatherCondition.Storm)
        assertTrue(storm.luminance() < 0.15)
        assertTrue(contrast(RadarColors.paper2, storm) >= 4.5)
        assertTrue(contrast(RadarColors.paper4, storm) >= 4.5)
        assertTrue(contrast(RadarColors.ink, storm) < 4.5)
    }

    @Test
    fun `only a thunder day with data takes pale ink`() {
        assertTrue(day(95).stripOnDark())
        assertFalse(day(80).stripOnDark())
        assertFalse(day(10).stripOnDark())
        assertFalse(day(-1).stripOnDark())
        assertFalse(day(120).stripOnDark())
    }

    @Test
    fun `no data means no tint`() {
        assertNull(day(-1).stripTint())
        assertNull(day(101).stripTint())
        assertEquals(conditionTint(WeatherCondition.Clear), day(0).stripTint())
        assertEquals(conditionTint(WeatherCondition.Storm), day(95).stripTint())
    }
}
