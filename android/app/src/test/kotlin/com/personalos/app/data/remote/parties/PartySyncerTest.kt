package com.personalos.app.data.remote.parties

import com.personalos.app.data.PartyDao
import com.personalos.app.data.PartyEntity
import com.personalos.app.data.PartySourceDao
import com.personalos.app.data.PartySourceEntity
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge contract: updates union aliases and preserve strongholds, new
 * slugs insert with their country, empty parses leave the clock alone, and
 * one country's failure never blocks the others.
 */
class PartySyncerTest {
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
        val rows: MutableList<PartySourceEntity> =
            mutableListOf(
                PartySourceEntity("India", "https://example.invalid/in", null),
                PartySourceEntity("Brazil", "https://example.invalid/br", null),
            ),
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

    private fun syncer(
        partyDao: FakePartyDao,
        sourceDao: FakeSourceDao,
        parsed: List<ParsedParty>,
        html: String = "<html><body></body></html>",
    ) = PartySyncer(
        partyDao = partyDao,
        sourceDao = sourceDao,
        parsers =
            mapOf(
                "India" to
                    object : CountryPartyParser {
                        override val country = "India"

                        override fun parse(doc: Document): List<ParsedParty> = parsed
                    },
            ),
        fetch = { html },
        now = { 1_000L },
    )

    @Test
    fun `update unions aliases, preserves stronghold, advances the clock`() =
        runBlocking {
            val partyDao =
                FakePartyDao(
                    mutableListOf(
                        PartyEntity("bjp", "India", "Bharatiya Janata Party", "BJP|Bharatiya Janata Party", "Gujarat", "national", 1L),
                    ),
                )
            val sourceDao = FakeSourceDao()
            val sync =
                syncer(
                    partyDao,
                    sourceDao,
                    parsed = listOf(ParsedParty("Bharatiya Janata Party", "BJP", "national")),
                )

            assertEquals(1, sync.syncCountry("India", "https://example.invalid/in"))

            val row = partyDao.rows.single()
            assertEquals("BJP|Bharatiya Janata Party", row.aliases)
            assertEquals("hand stronghold survives an empty parse", "Gujarat", row.stronghold)
            assertEquals(1_000L, row.updatedAt)
            assertEquals(1_000L, sourceDao.rows.first { it.country == "India" }.lastSyncAt)
        }

    @Test
    fun `a genuinely new party inserts with its country`() =
        runBlocking {
            val partyDao = FakePartyDao()
            val sourceDao = FakeSourceDao()
            // Parser is Brazil's: the test is about the insert path, and reusing
            // the India fake would collide on the same slug.
            val brazilSync =
                PartySyncer(
                    partyDao = partyDao,
                    sourceDao = sourceDao,
                    parsers =
                        mapOf(
                            "Brazil" to
                                object : CountryPartyParser {
                                    override val country = "Brazil"

                                    override fun parse(doc: Document): List<ParsedParty> = listOf(ParsedParty("Mission Party", "MISSÃO", "congress"))
                                },
                        ),
                    fetch = { "<html></html>" },
                    now = { 1_000L },
                )
            assertEquals(1, brazilSync.syncCountry("Brazil", "https://example.invalid/br"))

            val row = partyDao.rows.single()
            assertEquals("missao", row.slug)
            assertEquals("Brazil", row.country)
            assertTrue(row.aliases.split('|').containsAll(listOf("Mission Party", "MISSÃO")))
        }

    @Test
    fun `an empty parse leaves the clock untouched`() =
        runBlocking {
            val partyDao = FakePartyDao()
            val sourceDao = FakeSourceDao()
            val sync = syncer(partyDao, sourceDao, parsed = emptyList())

            assertEquals(0, sync.syncCountry("India", "https://example.invalid/in"))
            assertNull(sourceDao.rows.first { it.country == "India" }.lastSyncAt)
            assertTrue(partyDao.rows.isEmpty())
        }

    @Test
    fun `one country's failure never blocks the others`() =
        runBlocking {
            val partyDao = FakePartyDao()
            val sourceDao = FakeSourceDao()
            val sync =
                PartySyncer(
                    partyDao = partyDao,
                    sourceDao = sourceDao,
                    parsers =
                        mapOf(
                            "India" to
                                object : CountryPartyParser {
                                    override val country = "India"

                                    override fun parse(doc: Document): List<ParsedParty> = listOf(ParsedParty("Bharatiya Janata Party", null, "national"))
                                },
                            // Present so the fetch (which throws for Brazil)
                            // is what fails, not the parser lookup.
                            "Brazil" to
                                object : CountryPartyParser {
                                    override val country = "Brazil"

                                    override fun parse(doc: Document): List<ParsedParty> = emptyList()
                                },
                        ),
                    fetch = { url -> if ("br" in url) error("offline") else "<html></html>" },
                    now = { 1_000L },
                )

            val done = sync.syncAll()
            assertEquals(listOf("India"), done)
        }
}
