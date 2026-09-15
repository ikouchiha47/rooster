package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.mention.BundledPartySource
import com.personalos.app.core.mention.PartySource
import com.personalos.app.core.mention.PartySourceUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seeds the `parties` registry and its `party_sources` clocks from the bundled
 * source on first launch. Mirrors [PlacesSeeder]:
 *
 * Runs once: when the table is non-empty this is a single `COUNT(*)` and
 * nothing else. Writes are upsert/ignore, so an interrupted seed resumes
 * rather than duplicating, and a re-seed never touches sync clocks. Any
 * failure leaves empty tables and returns 0 — matching falls back to the
 * bundled source, it never crashes launch.
 */
class PartySeeder(
    private val partyDao: PartyDao,
    private val sourceDao: PartySourceDao,
    private val source: PartySource = BundledPartySource,
    private val urls: Map<String, String> = PartySourceUrls.ALL,
) {
    suspend fun seed(now: Long = System.currentTimeMillis()): Int =
        // Owns its dispatcher (see Retagger.run).
        withContext(Dispatchers.IO) {
            if (partyDao.count() > 0) return@withContext 0
            val entries =
                runCatching { source.parties() }
                    .onFailure { Log.w(TAG, "party seed failed", it) }
                    .getOrNull()
                    .orEmpty()
            if (entries.isEmpty()) return@withContext 0

            runCatching {
                partyDao.upsertAll(
                    entries.map { party ->
                        PartyEntity(
                            slug = party.slug,
                            country = party.country,
                            name = party.name,
                            aliases = party.aliases.joinToString("|"),
                            stronghold = party.stronghold,
                            recognition = party.recognition,
                            updatedAt = now,
                        )
                    },
                )
            }.onFailure { Log.w(TAG, "party seed insert failed", it) }

            runCatching {
                sourceDao.insertAll(urls.map { (country, url) -> PartySourceEntity(country, url, null) })
            }.onFailure { Log.w(TAG, "party source seed insert failed", it) }

            val seeded = entries.size
            Log.i(TAG, "seeded $seeded parties")
            seeded
        }

    private companion object {
        const val TAG = "Parties"
    }
}
