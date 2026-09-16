package com.personalos.app.data.remote

import android.util.Log
import com.personalos.app.core.enrich.ArticleSummary
import com.personalos.app.core.net.Http
import com.personalos.app.data.EnrichmentCandidate
import com.personalos.app.data.EventDao
import com.personalos.app.data.RuleItemSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Progressively fills in summaries for items that arrived without one.
 *
 * Measured need (2026-09-14): across the five news feeds, ~90% of items already
 * carry a summary. The gap is concentrated - **Indian Express ships an empty
 * `<description>`** (`<![CDATA[]]>`) for every item, and The Hindu's top feed
 * misses ~11%. So this is not a blanket backfill; it is a targeted one, and it
 * runs in bounded batches so it never competes with ingest.
 *
 * It never throws: a failed fetch is *recorded* so a dead link is not retried on
 * every pass, which is what keeps a batch bounded and stops a retry storm.
 */
class ArticleEnricher(
    private val dao: EventDao,
    private val fetch: (String) -> String = { url ->
        Http.getText(url, timeoutMs = REQUEST_TIMEOUT_MS, accept = "text/html")
    },
    private val pauseMs: Long = DEFAULT_PAUSE_MS,
    /**
     * Called once per batch with the items whose text actually grew, so rules
     * whose condition contains a text predicate can be re-evaluated against the
     * new content (ADR §10, R3). Defaults to a no-op, so enrichment works
     * standalone and cannot block on the rule engine.
     */
    private val onEnriched: suspend (List<RuleItemSeed>) -> Unit = {},
) {
    /** Returns how many items gained a summary. */
    suspend fun enrichBatch(limit: Int = DEFAULT_BATCH): Int =
        withContext(Dispatchers.IO) {
            val candidates: List<EnrichmentCandidate> = dao.enrichmentCandidates(limit)
            if (candidates.isEmpty()) return@withContext 0

            val enriched = ArrayList<RuleItemSeed>()
            for ((index, candidate) in candidates.withIndex()) {
                val summary =
                    runCatching { ArticleSummary.extract(fetch(candidate.url)) }
                        .onFailure { Log.w(TAG, "fetch failed: ${candidate.url}", it) }
                        .getOrNull()

                if (summary.isNullOrBlank()) {
                    // Marked attempted even on failure so this link is not
                    // re-fetched on the next pass.
                    dao.markEnrichmentAttempted(candidate.id, FAILED_ATTEMPT)
                } else {
                    dao.updateEnrichedContent(candidate.id, summary, System.currentTimeMillis())
                    // Only items that actually grew, with their new text: the
                    // rule engine never sees a failed attempt.
                    enriched +=
                        RuleItemSeed(
                            itemId = candidate.ulid,
                            sourceId = candidate.source,
                            title = candidate.title,
                            content = summary,
                        )
                }

                // Be a polite client: one request at a time, spaced out.
                if (index != candidates.lastIndex) delay(pauseMs)
            }

            if (enriched.isNotEmpty()) onEnriched(enriched)

            Log.i(TAG, "enriched ${enriched.size} of ${candidates.size} candidates")
            enriched.size
        }

    private companion object {
        const val TAG = "Enricher"
        const val DEFAULT_BATCH = 20
        const val DEFAULT_PAUSE_MS = 400L
        const val REQUEST_TIMEOUT_MS = 15_000

        /** Recorded in `enriched_at`: attempted, produced nothing. */
        const val FAILED_ATTEMPT = 0L
    }
}
