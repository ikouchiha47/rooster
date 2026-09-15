package com.personalos.app.data.remote.parties

import android.util.Log
import com.personalos.app.data.PartyDao
import com.personalos.app.data.PartyEntity
import com.personalos.app.data.PartySourceDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Keeps the `parties` registry current from the per-country list pages.
 *
 * Merge rules, in order per parsed row:
 *  1. **Match by name or shared alias** (normalized, length ≥ 3, not a party
 *     stopword) against the registry. A match *updates*: recognition refreshes,
 *     aliases **union** (the sync never deletes a hand-added alias like `GOP`
 *     that no table prints), stronghold fills only when empty, `updated_at`
 *     advances. This is the same union-not-replace precedent as the tagger
 *     vocabulary merge.
 *  2. **No match inserts** under [slugish] (table code preferred, English name
 *     fallback). This is how an emerging party arrives without a release.
 *  3. **A slug the sync never sees is left alone.** Its old `updated_at`
 *     marks it stale against the country's `last_sync_at`; nothing is
 *     deleted, because parties merge and split and one snapshot's absence is
 *     not proof of death.
 *
 * One country's failure never blocks the others, and the whole sync never
 * throws: the worker logs the count, matching never crashes launch.
 */
class PartySyncer(
    private val partyDao: PartyDao,
    private val sourceDao: PartySourceDao,
    private val parsers: Map<String, CountryPartyParser> = COUNTRY_PARSERS,
    private val fetch: (String) -> String = ::fetchPage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Syncs every registered source. Returns countries completed. */
    suspend fun syncAll(): List<String> =
        // Owns its dispatcher (see Retagger.run): the Sources screen calls this
        // from a Main-bound scope, and it fetches plus parses six pages.
        withContext(Dispatchers.IO) {
            val done = ArrayList<String>()
            for (source in sourceDao.all()) {
                runCatching { syncCountry(source.country, source.url) }
                    .onSuccess { done += source.country }
                    .onFailure { Log.w(TAG, "party sync failed for ${source.country}", it) }
            }
            done
        }

    /** Syncs one country. Returns rows written. Public for the manual trigger. */
    suspend fun syncCountry(
        country: String,
        url: String,
    ): Int =
        // Same ownership as syncAll: this is public for the manual trigger.
        withContext(Dispatchers.IO) {
            val parser = parsers[country] ?: return@withContext 0
            val startedAt = now()
            // Fetch and parse failures propagate: syncAll catches per country, so
            // a dead page excludes the country from "done" instead of counting a
            // zero-row no-op as completed.
            val html = fetch(url)
            val parsed = parser.parse(Jsoup.parse(html)).filter { it.englishName.isNotBlank() }
            if (parsed.isEmpty()) {
                // A renamed section or a failed fetch must degrade to a no-op, not
                // a wipe: never advance the clock on zero rows.
                Log.w(TAG, "party parse yielded nothing for $country, clock untouched")
                return@withContext 0
            }

            val existing = partyDao.all().filter { it.country == country }
            val byName = existing.associateBy { normalize(it.name) }
            val byAlias =
                existing
                    .flatMap { row -> row.aliases.split('|').map { normalize(it) to row } }
                    .filter { (alias, _) -> alias.length >= MIN_MATCH_LENGTH }
                    .toMap()
            // Working copies: matches later in the loop see earlier merges, so two
            // parsed rows hitting the same slug accumulate aliases instead of
            // clobbering each other.
            val working = existing.associateBy { it.slug }.toMutableMap()
            val changed = ArrayList<PartyEntity>()
            for (row in parsed) {
                val current = findMatch(row, byName, byAlias, working)
                if (current == null) {
                    // No match anywhere: a genuinely new party.
                    val slug = row.code?.let(::slugish)?.takeIf { it.isNotBlank() } ?: slugish(row.englishName)
                    if (slug.isBlank()) continue
                    val entity =
                        PartyEntity(
                            slug = slug,
                            country = country,
                            name = row.englishName,
                            aliases = candidateAliases(row).joinToString("|"),
                            stronghold = row.stronghold,
                            recognition = row.recognition,
                            updatedAt = startedAt,
                        )
                    working[slug] = entity
                    changed += entity
                    continue
                }
                val mergedAliases = (current.aliases.split('|') + candidateAliases(row)).distinct().joinToString("|")
                val updated =
                    current.copy(
                        aliases = mergedAliases,
                        recognition = row.recognition,
                        stronghold = current.stronghold.ifEmpty { row.stronghold },
                        updatedAt = startedAt,
                    )
                working[current.slug] = updated
                changed += updated
            }
            if (changed.isNotEmpty()) partyDao.upsertAll(changed)
            sourceDao.updateLastSync(country, startedAt)
            Log.i(TAG, "party sync $country: ${changed.size} rows")
            changed.size
        }

    private fun findMatch(
        row: ParsedParty,
        byName: Map<String, PartyEntity>,
        byAlias: Map<String, PartyEntity>,
        working: Map<String, PartyEntity>,
    ): PartyEntity? {
        byName[normalize(row.englishName)]?.let { return working[it.slug] }
        for (alias in candidateAliases(row)) {
            byAlias[normalize(alias)]?.let { return working[it.slug] }
        }
        return null
    }

    private fun candidateAliases(row: ParsedParty): List<String> {
        val aliases = ArrayList<String>()
        aliases += row.englishName
        row.code
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { aliases += it }
        return aliases
            .map { it.trim() }
            .filter { it.length >= MIN_MATCH_LENGTH }
    }

    private companion object {
        const val TAG = "Parties"
        const val MIN_MATCH_LENGTH = 3

        fun normalize(text: String): String = text.lowercase().trim()

        fun fetchPage(url: String): String =
            Jsoup
                .connect(url)
                .timeout(30_000)
                .get()
                .html()
    }
}
