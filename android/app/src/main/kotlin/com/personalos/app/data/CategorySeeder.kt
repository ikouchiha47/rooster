package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.category.Category
import com.personalos.app.core.category.CategoryRule
import com.personalos.app.core.category.CategoryTaxonomy
import com.personalos.app.core.category.CategoryTaxonomyJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** The bundled seed the taxonomy is first written from. */
const val CATEGORY_TAXONOMY_ASSET = "category/categorization.v1.json"

/**
 * Seeds `categories` and `category_rules` from the bundled asset on first run,
 * then reconciles on every launch. Mirrors [SourceSeeder]/[RuleSeeder], with one
 * difference: the bundle carries a `version`, so a **new release deploys its
 * updated rows** instead of leaving the old ones in place.
 *
 * **Version-gated.** The applied bundle version is kept in a [StringCache] under
 * [KEY]. When the bundled version equals the applied one, a launch is the cheap
 * add-only reconcile below. When it differs — a first install, or a release that
 * changed a rule — every bundled category and rule is **upserted** (the existing
 * row with that id is rewritten; a missing one is inserted) and the new version
 * is recorded. A row whose id is not in the bundle is never named, so a future
 * user-added category/rule survives untouched.
 *
 * **Add-only within a version, keyed by a stable id.** Each bundled row carries a
 * fixed id (e.g. `transactional`, `contact-wins`), and the same-version reconcile
 * inserts only the rows whose id is absent, so repeated launches are no-ops and a
 * later release's new category still reaches an existing install.
 *
 * **The asset is the seed, the DB is the runtime store.** Once seeded, the loader
 * ([CategoryStore]) reads the tables and never re-reads the asset; a stored row
 * is the owner of what the app runs on until a new bundle version deploys.
 *
 * The whole document is parsed (and therefore validated) before the first write,
 * so a malformed asset fails closed at 0 rather than shipping half a taxonomy.
 * The applied version is written only after the upsert succeeds, so an
 * interrupted run redoes the (idempotent) deploy on the next attempt. Any
 * failure returns 0 — nothing in ingest depends on these rows, and a bad seed
 * must never crash launch.
 */
class CategorySeeder(
    private val dao: CategoryDao,
    private val cache: StringCache,
    private val openAsset: () -> InputStream = { error("no category asset opened") },
) {
    suspend fun seed(): Int =
        // Owns its dispatcher (see Retagger.run).
        withContext(Dispatchers.IO) {
            runCatching {
                val taxonomy =
                    openAsset().use { CategoryTaxonomyJson.parse(it.readBytes().decodeToString()) }
                if (cache.read(KEY)?.value == taxonomy.version.toString()) {
                    addMissing(taxonomy)
                } else {
                    deployBundle(taxonomy)
                }
            }.onFailure { Log.w(TAG, "category seed failed", it) }
                .getOrDefault(0)
        }

    /**
     * The applied bundle version is the current one: keep today's add-only
     * reconcile. Only ids absent from the store are inserted, so a launch does a
     * single cheap read and a later release's extra row still lands.
     */
    private suspend fun addMissing(taxonomy: CategoryTaxonomy): Int {
        val storedCategories = dao.categories().map { it.id }.toSet()
        val storedRules = dao.rules().map { it.id }.toSet()
        val categories = taxonomy.categories.filterNot { it.id in storedCategories }
        val rules = taxonomy.rules.filterNot { it.id in storedRules }
        if (categories.isEmpty() && rules.isEmpty()) return 0
        if (categories.isNotEmpty()) dao.insertCategories(categories.map(Category::toEntity))
        if (rules.isNotEmpty()) dao.insertRules(rules.map(CategoryRule::toEntity))
        Log.i(TAG, "category seed: +${categories.size} categories, +${rules.size} rules")
        return categories.size + rules.size
    }

    /**
     * A new bundled version: rewrite every bundled row (update existing, insert
     * missing) and record the version. Rows the bundle does not name are not
     * touched, so user additions survive an upgrade.
     */
    private suspend fun deployBundle(taxonomy: CategoryTaxonomy): Int {
        dao.upsertCategories(taxonomy.categories.map(Category::toEntity))
        dao.upsertRules(taxonomy.rules.map(CategoryRule::toEntity))
        cache.write(KEY, taxonomy.version.toString(), System.currentTimeMillis())
        Log.i(
            TAG,
            "category seed: deployed v${taxonomy.version} " +
                "(${taxonomy.categories.size} categories, ${taxonomy.rules.size} rules)",
        )
        return taxonomy.categories.size + taxonomy.rules.size
    }

    companion object {
        /** The applied bundle version, so a later launch is a single cheap read. */
        const val KEY = "category:seed:version"

        private const val TAG = "Categories"
    }
}

private fun Category.toEntity(): CategoryEntity = CategoryEntity(id = id, label = label, sort = sort, color = color)

private fun CategoryRule.toEntity(): CategoryRuleEntity =
    CategoryRuleEntity(
        id = id,
        categoryId = categoryId,
        priority = priority,
        matcherType = matcherType,
        pattern = pattern,
        note = note,
    )
