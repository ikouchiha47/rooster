package com.personalos.app.data

import com.personalos.app.core.category.Categorizer
import com.personalos.app.core.category.Category
import com.personalos.app.core.category.CategoryRule
import com.personalos.app.core.category.CategoryTaxonomy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single runtime owner of the categorisation taxonomy: it seeds the tables
 * if needed, reads `categories`/`category_rules` and builds the pure core
 * [CategoryTaxonomy] the [Categorizer] runs on.
 *
 * The data layer owns this because the seed needs the bundled asset (an Android
 * resource); core stays android-free and only consumes the built object. The
 * taxonomy is fixed data, so it is built once and cached for the process.
 */
class CategoryStore(
    private val dao: CategoryDao,
    private val seeder: CategorySeeder,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: CategoryTaxonomy? = null

    /** The taxonomy, seeding the tables first if this is a fresh install. */
    suspend fun taxonomy(): CategoryTaxonomy {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: run {
                seeder.seed()
                build(dao.categories(), dao.rules()).also { cached = it }
            }
        }
    }

    /** The categorizer over the stored taxonomy — the one classifier the app runs. */
    suspend fun categorizer(): Categorizer = Categorizer(taxonomy())

    /** The built taxonomy, or null before [taxonomy] has run. */
    fun current(): CategoryTaxonomy? = cached

    companion object {
        /**
         * Builds the pure taxonomy from stored rows. `unknownId` is left to the
         * core default (`unknown`): the seed always carries an `unknown` row, and
         * `CategoryTaxonomy.unknown()` is a safe placeholder when it does not.
         */
        fun build(
            categories: List<CategoryEntity>,
            rules: List<CategoryRuleEntity>,
        ): CategoryTaxonomy =
            CategoryTaxonomy(
                id = "categorization",
                version = 1,
                categories = categories.map { Category(id = it.id, label = it.label, sort = it.sort, color = it.color) },
                rules =
                    rules.map {
                        CategoryRule(
                            id = it.id,
                            categoryId = it.categoryId,
                            priority = it.priority,
                            matcherType = it.matcherType,
                            pattern = it.pattern,
                            note = it.note,
                        )
                    },
            )
    }
}
