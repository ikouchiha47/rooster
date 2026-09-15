package com.personalos.app.data

import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.theme.RadarTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeRepositoryTest {
    @Test
    fun `load defaults to ibm_plex when nothing is stored`() {
        assertEquals(RadarTheme.IBM_PLEX, ThemeRepository(FakeCache()).load())
    }

    @Test
    fun `save then load round-trips the space theme`() {
        val repository = ThemeRepository(FakeCache())
        repository.save(RadarTheme.SPACE_GROTESK)
        assertEquals(RadarTheme.SPACE_GROTESK, repository.load())
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        val cache = FakeCache()
        cache.write(ThemeRepository.KEY, "noto", 0L)
        assertEquals(RadarTheme.IBM_PLEX, ThemeRepository(cache).load())
    }

    @Test
    fun `the stored value is the theme id`() {
        val cache = FakeCache()
        ThemeRepository(cache).save(RadarTheme.SPACE_GROTESK)
        assertEquals("space_grotesk", cache.read(ThemeRepository.KEY)?.value)
    }

    private class FakeCache : StringCache {
        private val values = mutableMapOf<String, StringCache.Entry>()

        override fun read(key: String): StringCache.Entry? = values[key]

        override fun write(
            key: String,
            value: String,
            at: Long,
        ) {
            values[key] = StringCache.Entry(value, at)
        }
    }
}
