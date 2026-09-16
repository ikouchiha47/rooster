package com.personalos.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository is the single owner that keeps bundled seeds add-only:
 * update/enable/delete on a `seeded = 1` row is rejected, on a user row it
 * works. The fake mirrors the DAO's `seeded = 0` SQL guards, so these tests
 * prove the contract, not just the fake.
 */
class RuleRepositoryTest {
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

    private fun dao() =
        FakeRuleDao(
            mutableListOf(
                RuleEntity(
                    id = "seed-1",
                    name = "Finance and rates watch",
                    enabled = true,
                    seeded = true,
                    conditionJson = """{"subject": "finance"}""",
                    actionJson = """{"delivery": "push", "position": 10}""",
                    position = 10,
                    createdAt = 1L,
                ),
                RuleEntity(
                    id = "user-1",
                    name = "Mine",
                    enabled = true,
                    seeded = false,
                    conditionJson = """{"source": "rss:mint-money"}""",
                    actionJson = """{"delivery": "none", "position": 0}""",
                    position = 0,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            ),
        )

    @Test
    fun `creating a rule validates before writing and writes a user rule`() =
        runBlocking {
            val dao = dao()
            val repository = RuleRepository(dao)

            val row = repository.create("Rates", SUBJECT_CONDITION, PUSH_ACTION, now = 2L)
            assertEquals(false, row.seeded)
            assertEquals(true, row.enabled)
            assertEquals("position comes from the action", 5L, row.position)
            assertEquals(2L, row.createdAt)
            assertEquals(2L, row.updatedAt)
            assertEquals(3, dao.rows.size)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.create("Bad", """{"subjekt": "finance"}""", PUSH_ACTION) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.create("Bad", SUBJECT_CONDITION, """{"delivery": "shout"}""") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.create("  ", SUBJECT_CONDITION, PUSH_ACTION) }
            }
            assertEquals("nothing invalid was stored", 3, dao.rows.size)
        }

    @Test
    fun `editing a user rule rewrites it and stamps updated_at`() =
        runBlocking {
            val dao = dao()
            RuleRepository(dao).update(
                id = "user-1",
                name = "  Renamed  ",
                conditionJson = SUBJECT_CONDITION,
                actionJson = """{"delivery": "push", "position": 7}""",
                now = 9L,
            )

            val row = dao.rows.first { it.id == "user-1" }
            assertEquals("Renamed", row.name)
            assertEquals(SUBJECT_CONDITION, row.conditionJson)
            assertEquals("position follows the edited action", 7L, row.position)
            assertEquals(9L, row.updatedAt)
        }

    @Test
    fun `editing with an invalid document is rejected before any write`() =
        runBlocking {
            val dao = dao()
            val repository = RuleRepository(dao)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.update("user-1", "X", """{"subjekt": "finance"}""", PUSH_ACTION) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.update("user-1", "X", SUBJECT_CONDITION, """{"delivery": "shout"}""") }
            }

            val row = dao.rows.first { it.id == "user-1" }
            assertEquals("name untouched", "Mine", row.name)
            assertEquals("condition untouched", """{"source": "rss:mint-money"}""", row.conditionJson)
            assertEquals("no edit stamped", 1L, row.updatedAt)
        }

    @Test
    fun `a user rule disables and deletes`() =
        runBlocking {
            val dao = dao()
            val repository = RuleRepository(dao)

            repository.setEnabled("user-1", false, now = 9L)
            val disabled = dao.rows.first { it.id == "user-1" }
            assertEquals(false, disabled.enabled)
            assertEquals(9L, disabled.updatedAt)

            repository.delete("user-1")
            assertEquals(listOf("seed-1"), dao.rows.map { it.id })
        }

    @Test
    fun `disabling a locked seed is rejected and leaves it enabled`() =
        runBlocking {
            val dao = dao()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { RuleRepository(dao).setEnabled("seed-1", false) }
            }
            assertTrue(dao.rows.first { it.id == "seed-1" }.enabled)
        }

    @Test
    fun `editing a locked seed is rejected and leaves the row unchanged`() =
        runBlocking {
            val dao = dao()
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    RuleRepository(dao).update("seed-1", "Hacked", SUBJECT_CONDITION, PUSH_ACTION)
                }
            }
            assertEquals("Finance and rates watch", dao.rows.first { it.id == "seed-1" }.name)
        }

    @Test
    fun `deleting a locked seed is rejected and keeps the row`() =
        runBlocking {
            val dao = dao()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { RuleRepository(dao).delete("seed-1") }
            }
            assertEquals(2, dao.rows.size)
        }

    @Test
    fun `an unknown id is rejected`() {
        runBlocking {
            val dao = dao()
            val repository = RuleRepository(dao)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.setEnabled("missing", false) }
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.update("missing", "X", SUBJECT_CONDITION, PUSH_ACTION) }
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.delete("missing") }
            }
        }
    }

    @Test
    fun `observe exposes the stored rules`() =
        runBlocking {
            val observed = RuleRepository(dao()).observe().first()
            assertEquals(listOf("seed-1", "user-1"), observed.map { it.id })
        }

    private companion object {
        const val SUBJECT_CONDITION = """{"subject": "finance"}"""
        const val PUSH_ACTION = """{"delivery": "push", "position": 5}"""
    }
}
