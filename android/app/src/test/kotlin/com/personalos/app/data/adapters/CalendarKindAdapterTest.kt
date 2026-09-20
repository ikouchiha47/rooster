package com.personalos.app.data.adapters

import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.calendar.CalendarProviders
import com.personalos.app.data.CalendarDao
import com.personalos.app.data.CalendarDateEntity
import com.personalos.app.data.SourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarKindAdapterTest {
    private class FakeCalendarCache : StringCache {
        val values = mutableMapOf<String, StringCache.Entry>()

        override fun read(key: String): StringCache.Entry? = values[key]

        override fun write(
            key: String,
            value: String,
            at: Long,
        ) {
            values[key] = StringCache.Entry(value, at)
        }
    }

    private class FakeCalendarDao : CalendarDao {
        // Keyed like the table: (source, uid) — a uid alone repeats across feeds.
        val rows = mutableMapOf<String, CalendarDateEntity>()
        var prunedSource: String? = null
        var prunedBefore: Long? = null

        override suspend fun upsertAll(rows: List<CalendarDateEntity>): List<Long> {
            rows.forEach { this.rows["${it.source}|${it.feedUid}"] = it }
            return rows.map { 1L }
        }

        override fun observeUpcoming(
            regions: List<String>,
            fromMs: Long,
            toMs: Long,
        ): Flow<List<CalendarDateEntity>> =
            flowOf(
                rows.values
                    .filter { it.region in regions && it.startsAt in fromMs..toMs }
                    .sortedBy { it.startsAt },
            )

        override suspend fun upcoming(
            regions: List<String>,
            fromMs: Long,
            toMs: Long,
        ): List<CalendarDateEntity> =
            rows.values
                .filter { it.region in regions && it.startsAt in fromMs..toMs }
                .sortedBy { it.startsAt }

        override suspend fun count(): Int = rows.size

        override suspend fun deleteStaleForSource(
            source: String,
            fetchedBefore: Long,
        ): Int {
            prunedSource = source
            prunedBefore = fetchedBefore
            val before = rows.size
            rows.entries.removeIf { it.value.source == source && it.value.fetchedAt < fetchedBefore }
            return before - rows.size
        }
    }

    private val feed =
        """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20261020
        SUMMARY:Durga Puja\, Maha Saptami
        UID:2026-10-20IN-WB3244regregion@www.officeholidays.com
        END:VEVENT
        BEGIN:VEVENT
        DTSTART;VALUE=DATE:20261002
        SUMMARY:Gandhi Jayanti
        UID:2026-10-02IN513regcountry@www.officeholidays.com
        END:VEVENT
        END:VCALENDAR
        """.trimIndent()

    private fun source(
        region: String,
        provider: String = "officeholidays",
    ): SourceEntity {
        val url = CalendarProviders.byId(provider)!!.urlFor(region)
        return SourceEntity(
            id = "user-cal-1",
            name = region,
            kind = "calendar",
            specJson = """{"provider":"$provider","region":"$region","url":"$url"}""",
            seeded = false,
            enabled = true,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }

    @Test
    fun `a region's feed lands as dated rows scoped to that region`() =
        runBlocking {
            val dao = FakeCalendarDao()
            val adapter = CalendarKindAdapter(dao, CachedBody(FakeCalendarCache()), fetch = { feed })
            assertEquals(2, adapter.ingest(source("india/west-bengal"), now = 1_000L))

            val durga = dao.rows.values.single { it.name == "Durga Puja, Maha Saptami" }
            assertEquals("india/west-bengal", durga.region)
            assertEquals("regional", durga.kind)
            assertEquals("calendar:india/west-bengal", durga.source)
            assertTrue("epoch ms at UTC midnight", durga.startsAt > 0)
            val national = dao.rows.values.single { it.name == "Gandhi Jayanti" }
            assertEquals("national", national.kind)
        }

    @Test
    fun `any region works, not just one country`() =
        runBlocking {
            val dao = FakeCalendarDao()
            val adapter = CalendarKindAdapter(dao, CachedBody(FakeCalendarCache()), fetch = { feed })
            adapter.ingest(source("japan"), now = 1_000L)
            adapter.ingest(source("germany/bavaria"), now = 1_000L)
            assertEquals(
                setOf("japan", "germany/bavaria"),
                dao.rows.values
                    .map { it.region }
                    .toSet(),
            )
        }

    @Test
    fun `a region slug is normalized before it becomes a region or a url`() {
        assertEquals("india/west-bengal", CalendarProviders.normalize("  India / West-Bengal/ "))
        assertEquals("india", CalendarProviders.normalize("INDIA"))
        assertEquals("", CalendarProviders.normalize("  "))
    }

    @Test
    fun `the resolved url carries the user's region`() {
        assertTrue(CalendarProviders.officeHolidays.urlFor("Japan").endsWith("/japan"))
        assertTrue(CalendarProviders.officeHolidays.urlFor("india/west-bengal").endsWith("/india/west-bengal"))
    }

    @Test
    fun `an unparseable feed keeps stored rows and never prunes`() =
        runBlocking {
            val dao = FakeCalendarDao()
            val adapter = CalendarKindAdapter(dao, CachedBody(FakeCalendarCache()), fetch = { "<html>not a calendar</html>" })
            assertEquals(0, adapter.ingest(source("india"), now = 5_000L))
            assertEquals(null, dao.prunedSource)
        }

    @Test
    fun `a successful sync prunes only its own region's stale rows`() =
        runBlocking {
            val dao = FakeCalendarDao()
            val adapter = CalendarKindAdapter(dao, CachedBody(FakeCalendarCache()), fetch = { feed })
            adapter.ingest(source("india/west-bengal"), now = 9_000L)
            assertEquals("calendar:india/west-bengal", dao.prunedSource)
            assertEquals(9_000L, dao.prunedBefore)
        }
}
