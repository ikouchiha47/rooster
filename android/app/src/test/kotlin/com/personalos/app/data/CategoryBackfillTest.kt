package com.personalos.app.data

import com.personalos.app.core.cache.ObservableStringCache
import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.category.Categorizer
import com.personalos.app.core.category.CategoryTaxonomyJson
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one-off re-classify: a row holding an old `SmsClass` name is rewritten to
 * the categorizer's new id, and the version gate stops it re-running.
 */
class CategoryBackfillTest {
    private val taxonomy =
        CategoryTaxonomyJson.parse(File("src/main/assets/category/categorization.v1.json").readText())

    private class FakeBackfillDao : CategoryBackfillDao {
        val candidates = mutableListOf<CategoryCandidate>()
        val categories = mutableMapOf<Long, String>()

        override suspend fun batch(
            afterId: Long,
            limit: Int,
        ): List<CategoryCandidate> = candidates.filter { it.id > afterId }.sortedBy { it.id }.take(limit)

        override suspend fun updateCategory(
            id: Long,
            category: String,
        ) {
            categories[id] = category
        }
    }

    private class FakeCache : ObservableStringCache {
        private val entries = mutableMapOf<String, StringCache.Entry>()
        private val signals = mutableMapOf<String, MutableStateFlow<StringCache.Entry?>>()

        private fun signal(key: String): MutableStateFlow<StringCache.Entry?> = signals.getOrPut(key) { MutableStateFlow(read(key)) }

        override fun read(key: String): StringCache.Entry? = entries[key]

        override fun write(
            key: String,
            value: String,
            at: Long,
        ) {
            val entry = StringCache.Entry(value, at)
            entries[key] = entry
            signal(key).value = entry
        }

        override fun observe(key: String): Flow<StringCache.Entry?> = signal(key)
    }

    private fun backfill(
        dao: FakeBackfillDao,
        cache: ObservableStringCache,
    ) = CategoryBackfill(dao, cache, { Categorizer(taxonomy) })

    @Test
    fun `an old promotional row is rewritten to the new promotions id`() =
        runBlocking {
            val dao = FakeBackfillDao()
            dao.candidates +=
                CategoryCandidate(
                    id = 1,
                    title = "VK-vONEMG",
                    content = "Hi, Rs.100 added to your account. Redeem it using code GIFT100 & get upto 25% off on medicines",
                )
            dao.categories[1] = "PROMOTIONAL"

            val updated = backfill(dao, FakeCache()).run()

            assertEquals(1, updated)
            assertEquals("promotions", dao.categories[1])
        }

    @Test
    fun `the walk rewrites every row and a second run is a no-op`() =
        runBlocking {
            val dao = FakeBackfillDao()
            dao.candidates += CategoryCandidate(1, "VM-BANKIN-S", "Rs 1214 debited from a/c 1234")
            dao.candidates += CategoryCandidate(2, "+919876543210", "Hey, are we still on for dinner?")
            val cache = FakeCache()
            val backfill = backfill(dao, cache)

            val first = backfill.run()

            assertEquals(2, first)
            assertEquals("transactional", dao.categories[1])
            assertEquals("personal", dao.categories[2])
            assertEquals(0, backfill.run())
        }

    @Test
    fun `a failure before completion leaves the version unwritten so it re-runs`() =
        runBlocking {
            val dao = FakeBackfillDao()
            dao.candidates += CategoryCandidate(1, "VM-BANKIN-S", "Rs 1214 debited from a/c 1234")
            val cache = FakeCache()

            // A fresh cache has no version; after a successful walk the gate is set.
            assertEquals(1, backfill(dao, cache).run())
            assertTrue(cache.read(CategoryBackfill.KEY)?.value == CategoryBackfill.VERSION)
        }

    /**
     * The signal the Messages list re-reads on: subscribed before the walk, it
     * must see the version the walk writes, not just the value at subscription.
     */
    @Test
    fun `observeVersion emits the version the walk writes`() =
        runBlocking {
            val dao = FakeBackfillDao()
            dao.candidates += CategoryCandidate(1, "VM-BANKIN-S", "Rs 1214 debited from a/c 1234")
            val cache = FakeCache()
            val backfill = backfill(dao, cache)

            val seen = async { backfill.observeVersion().first { it == CategoryBackfill.VERSION } }
            backfill.run()

            assertEquals(CategoryBackfill.VERSION, seen.await())
        }

    /**
     * A rule edit bumps the asset version, not [CategoryBackfill.VERSION]. The
     * gate folds the bundle version in, so such a release still re-derives
     * history; and a launch at the same bundle is a no-op.
     */
    @Test
    fun `a bundle version change re-runs the walk even when the base version is unchanged`() =
        runBlocking {
            val dao = FakeBackfillDao()
            dao.candidates += CategoryCandidate(1, "VM-BANKIN-S", "Rs 1214 debited from a/c 1234")
            val cache = FakeCache()
            var bundle = "1"
            val backfill = CategoryBackfill(dao, cache, { Categorizer(taxonomy) }, bundleVersion = { bundle })

            assertEquals(1, backfill.run())
            assertEquals("${CategoryBackfill.VERSION}.1", cache.read(CategoryBackfill.KEY)?.value)

            // Same rules generation, new bundle: the walk must run again.
            bundle = "2"
            assertEquals(1, backfill.run())
            assertEquals("${CategoryBackfill.VERSION}.2", cache.read(CategoryBackfill.KEY)?.value)

            // A launch at the same bundle is now a no-op.
            assertEquals(0, backfill.run())
        }
}
