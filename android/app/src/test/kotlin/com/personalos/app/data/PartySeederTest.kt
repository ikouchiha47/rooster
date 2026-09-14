package com.personalos.app.data

import com.personalos.app.core.mention.BundledPartySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartySeederTest {
    private class FakePartyDao(
        val rows: MutableList<PartyEntity> = mutableListOf(),
    ) : PartyDao {
        override suspend fun count(): Int = rows.size

        override suspend fun all(): List<PartyEntity> = rows.toList()

        override suspend fun upsertAll(parties: List<PartyEntity>) {
            for (party in parties) {
                rows.removeAll { it.slug == party.slug }
                rows += party
            }
        }
    }

    private class FakeSourceDao(
        val rows: MutableList<PartySourceEntity> = mutableListOf(),
    ) : PartySourceDao {
        override suspend fun all(): List<PartySourceEntity> = rows.toList()

        override suspend fun insertAll(sources: List<PartySourceEntity>) {
            for (source in sources) {
                if (rows.none { it.country == source.country }) rows += source
            }
        }

        override suspend fun updateLastSync(
            country: String,
            syncedAt: Long,
        ) {
            rows.replaceAll { if (it.country == country) it.copy(lastSyncAt = syncedAt) else it }
        }
    }

    @Test
    fun `empty tables seed the worldwide registry and its clocks`() =
        runBlocking {
            val parties = FakePartyDao()
            val sources = FakeSourceDao()
            val seeder = PartySeeder(parties, sources)

            assertTrue("the bundled worldwide seed is sizable", seeder.seed() > 90)
            assertEquals(BundledPartySource.parties().size, parties.rows.size)
            assertEquals(
                "one clock row per list page, all untouched",
                setOf("India", "Brazil", "Russia", "China", "South Africa", "United States"),
                sources.rows.map { it.country }.toSet(),
            )
            assertTrue(sources.rows.all { it.lastSyncAt == null })
            assertTrue("every row carries its country", parties.rows.all { it.country.isNotBlank() })
        }

    @Test
    fun `a non-empty table is a no-op that never touches clocks`() =
        runBlocking {
            val parties =
                FakePartyDao(
                    mutableListOf(
                        PartyEntity("bjp", "India", "Bharatiya Janata Party", "BJP", "Gujarat", "national", 1L),
                    ),
                )
            val sources =
                FakeSourceDao(
                    mutableListOf(PartySourceEntity("India", "https://example.invalid", 5L)),
                )
            val seeder = PartySeeder(parties, sources)

            assertEquals(0, seeder.seed())
            assertEquals(1, parties.rows.size)
            assertEquals("sync clock untouched", 5L, sources.rows.single().lastSyncAt)
        }
}
