package com.personalos.app.core.rules

import com.personalos.app.core.cache.StringCache
import com.personalos.app.data.EventEntity
import com.personalos.app.data.RuleBackfillDao
import com.personalos.app.data.RuleDao
import com.personalos.app.data.RuleEntity
import com.personalos.app.data.RuleItemSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule backfill, against a plain list. It is what makes a rule added or
 * edited *after* an item arrived report what it would have caught, so the three
 * things that make it safe are pinned here: it is bounded to the newest N, it
 * resumes from where a failure left it, and a second run at the same generation
 * does nothing.
 */
class RuleBackfillTest {
    private fun event(id: Long) =
        EventEntity(
            id = id,
            ulid = "u$id",
            dedupeKey = "d$id",
            source = "sms",
            type = "inbox",
            timestamp = id,
            title = "sender-$id",
            content = "body-$id",
        )

    private class FakeBackfillDao(
        private val items: List<EventEntity>,
    ) : RuleBackfillDao {
        override suspend fun maxId(): Long? = items.maxOfOrNull { it.id }

        override suspend fun before(
            beforeId: Long,
            limit: Int,
        ): List<EventEntity> = items.filter { it.id < beforeId }.sortedByDescending { it.id }.take(limit)
    }

    private class FakeRuleDao(
        private val rules: List<RuleEntity>,
    ) : RuleDao {
        override fun observeAll(): Flow<List<RuleEntity>> = flowOf(rules)

        override suspend fun all(): List<RuleEntity> = rules

        override suspend fun count(): Int = rules.size

        override suspend fun insertAll(rules: List<RuleEntity>): List<Long> = rules.map { 1L }

        override suspend fun updateUserOnly(
            id: String,
            name: String,
            conditionJson: String,
            actionJson: String,
            color: String?,
            position: Long,
            updatedAt: Long,
        ): Int = 0

        override suspend fun updateEnabledUserOnly(
            id: String,
            enabled: Boolean,
            updatedAt: Long,
        ): Int = 0

        override suspend fun deleteUserOnly(id: String): Int = 0
    }

    private class FakeCache : StringCache {
        private val map = mutableMapOf<String, StringCache.Entry>()

        override fun read(key: String): StringCache.Entry? = map[key]

        override fun write(
            key: String,
            value: String,
            at: Long,
        ) {
            map[key] = StringCache.Entry(value, at)
        }
    }

    private val rule =
        RuleEntity(
            id = "r1",
            name = "r1",
            enabled = true,
            seeded = false,
            conditionJson = """{"text":{"pattern":"strike","target":"any"}}""",
            actionJson = """{"delivery":"none","position":0}""",
            position = 0,
            createdAt = 1L,
        )

    private fun backfill(
        items: List<EventEntity>,
        rules: List<RuleEntity> = listOf(rule),
        cache: StringCache = FakeCache(),
        writeMatches: suspend (RuleEntity, List<RuleItemSeed>) -> Int,
    ) = RuleBackfill(FakeBackfillDao(items), FakeRuleDao(rules), writeMatches, cache)

    @Test
    fun `it evaluates the newest items first and is a no-op once done`() {
        val seen = mutableListOf<String>()
        val backfill =
            backfill(listOf(event(1), event(2), event(3))) { _, seeds ->
                seen += seeds.map { it.itemId }
                seeds.size
            }

        val written = runBlocking { backfill.run("r1", generation = 1L, batchSize = 2) }
        assertEquals(3, written)
        assertEquals(listOf("u3", "u2", "u1"), seen)

        // Same generation again: the done marker makes it a no-op.
        val again = runBlocking { backfill.run("r1", generation = 1L, batchSize = 2) }
        assertEquals(0, again)
        assertEquals("no second walk", 3, seen.size)
    }

    @Test
    fun `a new generation starts a fresh window`() {
        var evaluated = 0
        val backfill =
            backfill(listOf(event(1), event(2))) { _, seeds ->
                evaluated += seeds.size
                seeds.size
            }

        runBlocking { backfill.run("r1", generation = 1L) }
        runBlocking { backfill.run("r1", generation = 2L) }

        assertEquals("an edit re-walks", 4, evaluated)
    }

    @Test
    fun `a failure resumes from the persisted cursor, not the top`() {
        val seen = mutableListOf<List<String>>()
        var calls = 0
        val backfill =
            backfill(listOf(event(1), event(2), event(3), event(4))) { _, seeds ->
                calls++
                if (calls == 2) error("kill")
                seen += seeds.map { it.itemId }
                seeds.size
            }

        val firstRun = runBlocking { runCatching { backfill.run("r1", generation = 1L, batchSize = 2) } }
        assertTrue("the first walk failed", firstRun.isFailure)
        assertEquals("the first batch was written and persisted", listOf(listOf("u4", "u3")), seen)

        // A retry at the same generation must continue from the cursor, not redo u4/u3.
        val second = runBlocking { backfill.run("r1", generation = 1L, batchSize = 2) }
        assertEquals(2, second)
        assertEquals(listOf(listOf("u4", "u3"), listOf("u2", "u1")), seen)
    }

    @Test
    fun `it stops at the window cap`() {
        var evaluated = 0
        var batches = 0
        val items = (1L..6_000L).map { event(it) }
        val backfill =
            backfill(items) { _, seeds ->
                evaluated += seeds.size
                batches++
                seeds.size
            }

        runBlocking { backfill.run("r1", generation = 1L) }

        assertEquals("the window is bounded", 5_000, evaluated)
        assertEquals(25, batches)
    }

    @Test
    fun `an unknown rule writes nothing`() {
        var called = false
        val backfill =
            backfill(listOf(event(1)), rules = emptyList()) { _, _ ->
                called = true
                0
            }

        assertEquals(0, runBlocking { backfill.run("missing", generation = 1L) })
        assertTrue("no rule, no evaluation", !called)
    }
}
