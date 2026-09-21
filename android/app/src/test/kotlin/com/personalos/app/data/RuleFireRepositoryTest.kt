package com.personalos.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Watchers feed's reads. Behaviour that matters to a reader:
 *  - a fire says **what matched**, from the same stored facts ingest evaluated;
 *  - a broken rule never hides a working one;
 *  - one batch of fires costs one read per table, not one per fire.
 */
class RuleFireRepositoryTest {
    // ------------------------------------------------------------------ fakes

    private class FakeRuleDao(
        private val rows: List<RuleEntity>,
    ) : RuleDao {
        override fun observeAll(): Flow<List<RuleEntity>> = flowOf(rows)

        override suspend fun all(): List<RuleEntity> = rows

        override suspend fun count(): Int = rows.size

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

    private class FakeRuleFireDao(
        private val fires: Map<String, List<ItemRuleEntity>>,
        private val events: Map<String, EventEntity>,
    ) : RuleFireDao {
        var eventBatchCalls = 0
        var lastLimit: Int? = null

        override suspend fun firesForRule(
            ruleId: String,
            limit: Int,
        ): List<ItemRuleEntity> {
            lastLimit = limit
            return fires[ruleId].orEmpty().take(limit)
        }

        override suspend fun eventsByUlids(ulids: List<String>): List<EventEntity> {
            eventBatchCalls++
            return ulids.mapNotNull { events[it] }
        }
    }

    private open class StubItemTagDao : ItemTagDao {
        override suspend fun insertAll(tags: List<ItemTagEntity>): List<Long> = tags.map { 1L }

        override suspend fun register(tagger: TaggerEntity) = Unit

        override suspend fun deactivateAll() = Unit

        override suspend fun activeTagger(): TaggerEntity? = null

        override suspend fun countForTagger(taggerId: String): Int = 0

        override fun observeTagCounts(): Flow<List<TagCount>> = flowOf(emptyList())

        override suspend fun itemsForTag(
            tag: String,
            taggerId: String,
            limit: Int,
        ): List<String> = emptyList()

        override suspend fun deleteAll() = Unit

        override suspend fun tagsForItems(itemIds: List<String>): List<ItemTagRow> = emptyList()
    }

    private open class StubMentionDao : MentionDao {
        override suspend fun insertAll(mentions: List<MentionEntity>): List<Long> = mentions.map { 1L }

        override suspend fun forItem(itemId: String): List<MentionEntity> = emptyList()

        override suspend fun forItems(itemIds: List<String>): List<MentionEntity> = emptyList()

        override suspend fun missingItems(
            afterId: Long,
            limit: Int,
        ): List<MentionCandidate> = emptyList()

        override suspend fun deleteSurfaces(surfaces: List<String>): Int = 0
    }

    private class FieldsDao(
        private val fields: Map<String, List<ItemFieldEntity>>,
    ) : ItemFieldDao {
        override suspend fun insertAll(fields: List<ItemFieldEntity>): List<Long> = fields.map { 1L }

        override suspend fun forItem(itemId: String): List<ItemFieldEntity> = fields[itemId].orEmpty()

        override suspend fun forItems(itemIds: List<String>): List<ItemFieldEntity> = itemIds.flatMap { fields[it].orEmpty() }

        override suspend fun replaceAll(fields: List<ItemFieldEntity>) = Unit

        override suspend fun deleteForItem(itemId: String) = Unit

        override suspend fun seriesWindow(
            sourceId: String,
            field: String,
            limit: Int,
        ): List<SeriesSample> = emptyList()
    }

    private class StubItemRuleDao : ItemRuleDao {
        override fun observeAll(): Flow<List<ItemRuleEntity>> = flowOf(emptyList())

        override suspend fun all(): List<ItemRuleEntity> = emptyList()

        override suspend fun insertAll(matches: List<ItemRuleEntity>): List<Long> = matches.map { 1L }
    }

    // ----------------------------------------------------------------- helpers

    private fun rule(
        id: String,
        name: String = id,
        conditionJson: String = """{"field":{"name":"temp_c","op":"gt","value":40}}""",
    ) = RuleEntity(
        id = id,
        name = name,
        enabled = true,
        seeded = false,
        conditionJson = conditionJson,
        actionJson = """{"delivery":"none","position":0}""",
        position = 0L,
        createdAt = 0L,
    )

    private fun event(
        ulid: String,
        title: String = "Bengaluru",
        source: String = "weather:bengaluru",
    ) = EventEntity(
        ulid = ulid,
        dedupeKey = "k-$ulid",
        source = source,
        type = "observation",
        timestamp = 1_000L,
        title = title,
        content = "40.2C",
    )

    private fun repository(
        rules: List<RuleEntity>,
        fires: Map<String, List<ItemRuleEntity>>,
        events: Map<String, EventEntity>,
        fields: Map<String, List<ItemFieldEntity>> = emptyMap(),
    ): Pair<RuleFireRepository, FakeRuleFireDao> {
        val fireDao = FakeRuleFireDao(fires, events)
        val writer =
            RuleWriter(
                FakeRuleDao(rules),
                StubItemRuleDao(),
                StubItemTagDao(),
                StubMentionDao(),
                FieldsDao(fields),
            )
        return RuleFireRepository(FakeRuleDao(rules), fireDao, writer) to fireDao
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun `a fire carries the clause and the stored value that satisfied it`() =
        runBlocking {
            val (repo, _) =
                repository(
                    rules = listOf(rule("hot", "Hot day")),
                    fires = mapOf("hot" to listOf(ItemRuleEntity("obs-1", "hot", 500L))),
                    events = mapOf("obs-1" to event("obs-1")),
                    fields = mapOf("obs-1" to listOf(ItemFieldEntity("obs-1", "temp_c", valueNum = 40.2))),
                )
            val group = repo.watchers().single()
            assertEquals("Hot day", group.rule.name)
            assertEquals(
                listOf("temp_c 40.2 > 40"),
                group.fires
                    .single()
                    .clauses
                    .map { it.summary },
            )
        }

    @Test
    fun `a rule that never fired is still returned, with no fires`() =
        runBlocking {
            val (repo, _) = repository(listOf(rule("quiet", "Quiet")), emptyMap(), emptyMap())
            val group = repo.watchers().single()
            assertEquals("Quiet", group.rule.name)
            assertTrue(group.fires.isEmpty())
        }

    @Test
    fun `an unreadable rule does not hide another rule's fires`() =
        runBlocking {
            val (repo, _) =
                repository(
                    rules =
                        listOf(
                            rule("broken", "Broken", conditionJson = """{"subjekt":"typo"}"""),
                            rule("hot", "Hot day"),
                        ),
                    fires =
                        mapOf(
                            "broken" to listOf(ItemRuleEntity("obs-1", "broken", 400L)),
                            "hot" to listOf(ItemRuleEntity("obs-1", "hot", 500L)),
                        ),
                    events = mapOf("obs-1" to event("obs-1")),
                    fields = mapOf("obs-1" to listOf(ItemFieldEntity("obs-1", "temp_c", valueNum = 40.2))),
                )
            val byRule = repo.watchers().associateBy { it.rule.id }
            // The working rule is untouched...
            assertEquals(1, byRule.getValue("hot").fires.size)
            assertEquals(
                listOf("temp_c 40.2 > 40"),
                byRule
                    .getValue("hot")
                    .fires
                    .single()
                    .clauses
                    .map { it.summary },
            )
            // ...and the broken rule's fire is still an event that happened: it is
            // shown, just without a reason it cannot compute.
            assertEquals(
                "a fire from a rule with unreadable JSON is reported, not dropped",
                1,
                byRule.getValue("broken").fires.size,
            )
            assertTrue(
                byRule
                    .getValue("broken")
                    .fires
                    .single()
                    .clauses
                    .isEmpty(),
            )
        }

    @Test
    fun `a fire whose item is gone is skipped without failing the rest`() =
        runBlocking {
            val (repo, _) =
                repository(
                    rules = listOf(rule("hot", "Hot day")),
                    fires =
                        mapOf(
                            "hot" to
                                listOf(
                                    ItemRuleEntity("missing", "hot", 600L),
                                    ItemRuleEntity("obs-1", "hot", 500L),
                                ),
                        ),
                    events = mapOf("obs-1" to event("obs-1")),
                    fields = mapOf("obs-1" to listOf(ItemFieldEntity("obs-1", "temp_c", valueNum = 40.2))),
                )
            val fires = repo.watchers().single().fires
            assertEquals(1, fires.size)
            assertEquals("obs-1", fires.single().itemId)
        }

    @Test
    fun `every fire batch costs one event read, and honours the per-rule cap`() =
        runBlocking {
            val many = (1..5).map { ItemRuleEntity("obs-$it", "hot", 500L + it) }
            val (repo, fireDao) =
                repository(
                    rules = listOf(rule("hot", "Hot day")),
                    fires = mapOf("hot" to many),
                    events = (1..5).associate { "obs-$it" to event("obs-$it") },
                    fields = (1..5).associate { "obs-$it" to listOf(ItemFieldEntity("obs-$it", "temp_c", valueNum = 40.2)) },
                )
            val group = repo.watchers(perRule = 2).single()
            assertEquals(2, group.fires.size)
            assertEquals(2, fireDao.lastLimit)
            assertEquals(1, fireDao.eventBatchCalls)
        }
}
