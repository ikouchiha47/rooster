package com.personalos.app.ui.weather

import com.personalos.app.core.model.WeatherDay
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherCodeTest {
    @Test
    fun `wmo codes map to real states`() {
        assertEquals(WeatherCondition.Clear, weatherCodeCondition(0))
        assertEquals(WeatherCondition.Clear, weatherCodeCondition(1))
        assertEquals(WeatherCondition.PartlyCloudy, weatherCodeCondition(2))
        assertEquals(WeatherCondition.Cloudy, weatherCodeCondition(3))
        assertEquals(WeatherCondition.Fog, weatherCodeCondition(45))
        assertEquals(WeatherCondition.Drizzle, weatherCodeCondition(53))
        assertEquals(WeatherCondition.Rain, weatherCodeCondition(63))
        assertEquals(WeatherCondition.Showers, weatherCodeCondition(80))
        assertEquals(WeatherCondition.Snow, weatherCodeCondition(73))
        assertEquals(WeatherCondition.Storm, weatherCodeCondition(95))
        assertEquals(WeatherCondition.Storm, weatherCodeCondition(99))
    }

    @Test
    fun `a day prefers its code over the rain bands`() {
        val stormByCode =
            WeatherDay(date = "2026-09-17", maxC = 30, minC = 20, rainChance = 10, weatherCode = 95).condition()
        assertEquals(WeatherCondition.Storm, stormByCode)
        val clearByFallback =
            WeatherDay(date = "2026-09-17", maxC = 30, minC = 20, rainChance = 10).condition()
        assertEquals(WeatherCondition.Clear, clearByFallback)
    }
}
