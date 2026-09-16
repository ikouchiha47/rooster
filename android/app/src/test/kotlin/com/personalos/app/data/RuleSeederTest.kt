package com.personalos.app.data

import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.rules.ActionJson
import com.personalos.app.core.rules.Condition
import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.Delivery
import com.personalos.app.core.rules.FieldNames
import com.personalos.app.core.rules.RuleEvaluator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seeder validates every bundled rule before it inserts, so a typo in the
 * bundled data fails closed (0 rows) rather than shipping a rule that silently
 * never matches. These tests run the real seed on an empty fake and then assert
 * the shipped rows themselves.
 */
class RuleSeederTest {
    private class FakeRuleDao(
        val rows: MutableList<RuleEntity> = mutableListOf(),
    ) : RuleDao {
        override fun observeAll(): Flow<List<RuleEntity>> = flowOf(rows.toList())

        override suspend fun all(): List<RuleEntity> = rows.toList()

        override suspend fun count(): Int = rows.size

        override suspend fun insertAll(rules: List<RuleEntity>): List<Long> =
            rules.map { rule ->
                if (rows.none { it.id == rule.id }) {
                    rows += rule
                    1L
                } else {
                    -1L
                }
            }

        override suspend fun updateUserOnly(
            id: String,
            name: String,
            conditionJson: String,
            actionJson: String,
            position: Long,
            updatedAt: Long,
        ): Int {
            val index = rows.indexOfFirst { it.id == id && !it.seeded }
            if (index == -1) return 0
            rows[index] =
                rows[index].copy(
                    name = name,
                    conditionJson = conditionJson,
                    actionJson = actionJson,
                    position = position,
                    updatedAt = updatedAt,
                )
            return 1
        }

        override suspend fun updateEnabledUserOnly(
            id: String,
            enabled: Boolean,
            updatedAt: Long,
        ): Int {
            val index = rows.indexOfFirst { it.id == id && !it.seeded }
            if (index == -1) return 0
            rows[index] = rows[index].copy(enabled = enabled, updatedAt = updatedAt)
            return 1
        }

        override suspend fun deleteUserOnly(id: String): Int = if (rows.removeIf { it.id == id && !it.seeded }) 1 else 0
    }

    /** Every `source` id a condition names, recursing through `all` / `any`. */
    private fun sourceIds(condition: Condition): List<String> =
        when (condition) {
            is Condition.All -> condition.conditions.flatMap(::sourceIds)
            is Condition.Any -> condition.conditions.flatMap(::sourceIds)
            is Condition.Source -> listOf(condition.sourceId)
            else -> emptyList()
        }

    /** Every `field` name a condition names, recursing through `all` / `any`. */
    private fun fieldNames(condition: Condition): List<String> =
        when (condition) {
            is Condition.All -> condition.conditions.flatMap(::fieldNames)
            is Condition.Any -> condition.conditions.flatMap(::fieldNames)
            is Condition.Field -> listOf(condition.name)
            else -> emptyList()
        }

    @Test
    fun `an empty table seeds the bundled rules as locked and enabled`() =
        runBlocking {
            val dao = FakeRuleDao()
            assertEquals(5, RuleSeeder(dao).seed(now = 1L))
            assertEquals(5, dao.rows.size)
            assertTrue("every seed is locked", dao.rows.all { it.seeded })
            assertTrue("every seed is enabled", dao.rows.all { it.enabled })
            assertEquals(
                "ids are unique",
                5,
                dao.rows
                    .map { it.id }
                    .toSet()
                    .size,
            )
            assertEquals(
                "positions are distinct",
                5,
                dao.rows
                    .map { it.position }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun `every bundled seed parses and is item-evaluable`() =
        runBlocking {
            val dao = FakeRuleDao()
            assertEquals(5, RuleSeeder(dao).seed(now = 1L))

            // A malformed or series seed would throw here, so this fails a typo
            // in the bundled data the same way the seed itself does.
            dao.rows.forEach { row ->
                val condition = ConditionJson.parse(row.conditionJson)
                ActionJson.parse(row.actionJson)
                RuleEvaluator.requireItemEvaluable(condition)
            }
        }

    @Test
    fun `no bundled seed names a field no producer supplies`() =
        runBlocking {
            val dao = FakeRuleDao()
            RuleSeeder(dao).seed(now = 1L)

            val named = dao.rows.flatMap { fieldNames(ConditionJson.parse(it.conditionJson)) }
            assertTrue("a seed now exercises a supplied field", named.any { it in FieldNames.SUPPLIED })

            // The remaining limitation: a `field` whose name no producer writes
            // would evaluate to false forever. Only a supplied name may ship.
            named.forEach { name ->
                assertTrue(
                    "'$name' is not supplied by any producer; supplied: ${FieldNames.SUPPLIED.sorted()}",
                    name in FieldNames.SUPPLIED,
                )
            }
        }

    @Test
    fun `a malformed seed is rejected before it becomes a row`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleSeed("Bad condition", """{"subjekt": "finance"}""", """{"delivery": "push"}""").toEntity(1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuleSeed("Bad action", """{"subject": "finance"}""", """{"delivery": "shout"}""").toEntity(1L)
        }
    }

    @Test
    fun `the seeds name only source ids a producer actually emits`() =
        runBlocking {
            val dao = FakeRuleDao()
            RuleSeeder(dao).seed(now = 1L)

            val named =
                dao.rows.flatMap { sourceIds(ConditionJson.parse(it.conditionJson)) }
            assertTrue("the finance watch names a source", named.isNotEmpty())

            val catalogIds = FeedCatalog.SEEDS.map { it.id }.toSet()
            named.forEach { sourceId ->
                // Catalog feeds are `rss:<id>`; SMS rows carry `sms` directly,
                // not a prefixed catalog id.
                val isCatalog = sourceId.removePrefix(FeedCatalog.SOURCE_PREFIX) in catalogIds
                assertTrue(
                    "'$sourceId' is not emitted by any producer",
                    isCatalog || sourceId == SmsSource.SOURCE_ID,
                )
            }
        }

    @Test
    fun `one seed surfaces without interrupting`() =
        runBlocking {
            val dao = FakeRuleDao()
            RuleSeeder(dao).seed(now = 1L)

            val suppressed = dao.rows.filter { ActionJson.parse(it.actionJson).delivery == Delivery.NONE }
            assertEquals("exactly one rule must be delivery=none", 1, suppressed.size)
        }

    @Test
    fun `a non-empty table is a no-op`() =
        runBlocking {
            val dao =
                FakeRuleDao(
                    mutableListOf(
                        RuleEntity(
                            id = "id-1",
                            name = "Mine",
                            enabled = true,
                            seeded = false,
                            conditionJson = """{"subject": "finance"}""",
                            actionJson = """{"delivery": "none", "position": 0}""",
                            position = 0,
                            createdAt = 1L,
                        ),
                    ),
                )
            assertEquals(0, RuleSeeder(dao).seed())
            assertEquals(1, dao.rows.size)
            assertEquals("id-1", dao.rows.single().id)
        }

    @Test
    fun `seeds write both stamps`() =
        runBlocking {
            val dao = FakeRuleDao()
            RuleSeeder(dao).seed(now = 7L)
            assertTrue("created_at written", dao.rows.all { it.createdAt == 7L })
            assertTrue("updated_at written", dao.rows.all { it.updatedAt == 7L })
        }
}
