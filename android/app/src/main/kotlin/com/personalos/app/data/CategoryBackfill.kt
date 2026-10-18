package com.personalos.app.data

import com.personalos.app.core.cache.ObservableStringCache
import com.personalos.app.core.category.Categorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Re-classifies every stored SMS row **once**, in Kotlin, through the
 * [Categorizer]. Rows written before the data-driven taxonomy hold old
 * `SmsClass` names (`PROMOTIONAL`, `BILL`, …); this rewrites them to the new
 * category ids (`promotions`, `transactional`, …). The rule logic is never
 * duplicated in SQL — the categorizer is the one owner, and this walk only
 * moves rows through it.
 *
 * ## Versioned so it does not re-run
 *
 * The pass is gated by a stored version string ([KEY] / [VERSION]), mirroring
 * the prefs-gated one-shots in `BackfillWorker` (the stopword cleanup and rule
 * sweep). Once the version is written, later launches do a single cheap read
 * and stop.
 *
 * The gate also folds in the **applied bundle version** ([bundleVersion]): a
 * release that changes a rule bumps the asset's `version`, and the seeder
 * upserts the new rows — but a category id in an old SMS row must be re-derived
 * from the new rules, or history keeps its old classification. Folding the
 * bundle version into the gate ties the two together structurally: any rule
 * change that ships also triggers this walk, without a second version to bump by
 * hand. Bump [VERSION] for a re-derive unrelated to the bundle.
 *
 * The version is written only after the walk completes, so an interrupted run
 * (a killed worker) resumes from the start on the next attempt rather than
 * leaving half the rows stale. The walk is idempotent, so redoing it is safe.
 */
class CategoryBackfill(
    private val dao: CategoryBackfillDao,
    private val cache: ObservableStringCache,
    /** The one classifier; resolved lazily so the taxonomy is seeded first. */
    private val categorizer: suspend () -> Categorizer,
    /** Whether a sender is a saved contact — the contact-wins rule's input. */
    private val isContact: (String) -> Boolean = { false },
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    /**
     * The applied bundle version, folded into the gate so a rule-version change
     * forces a re-derive even when [VERSION] is untouched. Null before any
     * bundle has been deployed (a fresh store, or a unit test with no seeder).
     */
    private val bundleVersion: () -> String? = { null },
) {
    /**
     * The stored version as a signal: emits the current value and then again
     * whenever this walk writes it. The Messages list reads its timeline with a
     * suspend query, so it re-reads on this — a re-classify that lands while the
     * list is open otherwise stays invisible until the next launch.
     */
    fun observeVersion(): Flow<String?> = cache.observe(KEY).map { it?.value }

    suspend fun run(): Int =
        // Owns its dispatcher (see Retagger.run).
        withContext(Dispatchers.IO) {
            val version = gate()
            if (cache.read(KEY)?.value == version) return@withContext 0
            val classifier = categorizer()
            var afterId = 0L
            var updated = 0
            while (true) {
                val batch = dao.batch(afterId, batchSize)
                if (batch.isEmpty()) break
                for (row in batch) {
                    val category = classifier.categorize(row.title, row.content, isContact(row.title)).id
                    dao.updateCategory(row.id, category)
                }
                afterId = batch.last().id
                updated += batch.size
            }
            cache.write(KEY, version, System.currentTimeMillis())
            updated
        }

    /**
     * The stored gate: [VERSION] alone, or `VERSION.bundle` once a bundle has
     * been deployed. Folding the bundle in means a rule-version change re-derives
     * history even when [VERSION] is not bumped.
     */
    private fun gate(): String = bundleVersion()?.let { "$VERSION.$it" } ?: VERSION

    companion object {
        const val KEY = "category:reclassify"

        /** The taxonomy/rules generation this pass writes. Bump to re-run. */
        const val VERSION = "2"

        private const val DEFAULT_BATCH_SIZE = 500
    }
}
