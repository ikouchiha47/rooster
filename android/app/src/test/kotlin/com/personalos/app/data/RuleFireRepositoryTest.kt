package com.personalos.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

        override suspend fun countForRule(ruleId: String): Int = fires[ruleId].orEmpty().size

        /**
         * Models the real cross-rule query: one row per matched event, the rules
         * folded into `rule_ids`, ordered by `(event ts desc, event id desc)`.
         */
        override suspend fun alertsPage(
            cursorTs: Long?,
            cursorId: Long,
            limit: Int,
        ): List<AlertCandidate> =
            fires
                .values
                .flatten()
                .filter { events.containsKey(it.itemId) }
                .groupBy { it.itemId }
                .mapNotNull { (itemId, rows) ->
                    val event = events[itemId] ?: return@mapNotNull null
                    AlertCandidate(
                        itemId = itemId,
                        eventId = event.id,
                        matchedAt = rows.minOf { it.matchedAt },
                        itemTimestamp = event.timestamp,
                        ruleIds = rows.joinToString(",") { it.ruleId },
                    )
                }.sortedWith(
                    compareByDescending<AlertCandidate> { it.itemTimestamp }
                        .thenByDescending { it.eventId },
                ).filter { candidate ->
                    cursorTs == null ||
                        candidate.itemTimestamp < cursorTs ||
                        (candidate.itemTimestamp == cursorTs && candidate.eventId < cursorId)
                }.take(limit)

        override fun observeAlertCount(): Flow<Int> =
            flowOf(
                fires.values
                    .flatten()
                    .map { it.itemId }
                    .distinct()
                    .size,
            )

        /** Models the real keyset: by the item's time desc, id desc, older than the cursor. */
        override suspend fun firesForRulePage(
            ruleId: String,
            cursorTs: Long?,
            cursorId: String,
            limit: Int,
        ): List<RuleFireCandidate> =
            fires[ruleId]
                .orEmpty()
                .mapNotNull { fire -> events[fire.itemId]?.let { fire to it.timestamp } }
                .sortedWith(
                    compareByDescending<Pair<ItemRuleEntity, Long>> { it.second }
                        .thenByDescending { it.first.itemId },
                ).filter { (fire, ts) ->
                    cursorTs == null || ts < cursorTs || (ts == cursorTs && fire.itemId < cursorId)
                }.take(limit)
                .map { (fire, ts) ->
                    val event = events.getValue(fire.itemId)
                    RuleFireCandidate(
                        itemId = fire.itemId,
                        matchedAt = fire.matchedAt,
                        title = event.title,
                        source = event.source,
                        content = event.content,
                        itemTimestamp = ts,
                    )
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

    @Test
    fun `forRulePage walks by cursor without skipping or duplicating`() =
        runBlocking {
            // Five fires whose items have distinct times, so the order is total and
            // the cursor is unambiguous.
            val fires = (1..5).map { ItemRuleEntity("obs-$it", "hot", 500L + it) }
            val events = (1..5).associate { "obs-$it" to event("obs-$it").copy(timestamp = it.toLong()) }
            val (repo, _) =
                repository(
                    rules = listOf(rule("hot", "Hot day")),
                    fires = mapOf("hot" to fires),
                    events = events,
                    fields = (1..5).associate { "obs-$it" to listOf(ItemFieldEntity("obs-$it", "temp_c", valueNum = 40.2)) },
                )

            val first = repo.forRulePage("hot", cursorTs = null, cursorId = "", limit = 2)
            assertEquals(listOf("obs-5", "obs-4"), first.fires.map { it.itemId })
            assertTrue("a third page exists", first.hasMore)

            val second = repo.forRulePage("hot", first.nextTs!!, first.nextId!!, limit = 2)
            assertEquals(listOf("obs-3", "obs-2"), second.fires.map { it.itemId })
            assertTrue(second.hasMore)

            val third = repo.forRulePage("hot", second.nextTs!!, second.nextId!!, limit = 2)
            assertEquals(listOf("obs-1"), third.fires.map { it.itemId })
            assertTrue("the last page is not the first", !third.hasMore)
        }

    @Test
    fun `alerts names every rule that matched an event, newest event first`() =
        runBlocking {
            val (repo, _) =
                repository(
                    rules = listOf(rule("hot", "Hot day"), rule("cold", "Cold day")),
                    fires =
                        mapOf(
                            "hot" to
                                listOf(
                                    ItemRuleEntity("obs-1", "hot", 500L),
                                    ItemRuleEntity("obs-2", "hot", 600L),
                                ),
                            "cold" to listOf(ItemRuleEntity("obs-1", "cold", 501L)),
                        ),
                    events =
                        mapOf(
                            "obs-1" to event("obs-1").copy(id = 1, timestamp = 100L),
                            "obs-2" to event("obs-2").copy(id = 2, timestamp = 200L),
                        ),
                    fields =
                        mapOf(
                            "obs-1" to listOf(ItemFieldEntity("obs-1", "temp_c", valueNum = 40.2)),
                            "obs-2" to listOf(ItemFieldEntity("obs-2", "temp_c", valueNum = 41.0)),
                        ),
                )
            val alerts = repo.alerts(cursorTs = null, cursorId = 0L).alerts
            assertEquals("one row per event, new event first", listOf("obs-2", "obs-1"), alerts.map { it.itemId })
            assertEquals(
                "both rules ride on the one event, ordered by name so the row is stable",
                listOf("Cold day", "Hot day"),
                alerts[1].rules.map { it.name },
            )
            assertEquals(
                "each rule carries the clause that held",
                listOf("temp_c 40.2 > 40"),
                alerts[1]
                    .rules
                    .first()
                    .clauses
                    .map { it.summary },
            )
        }

    @Test
    fun `alerts walks by cursor without skipping or duplicating`() =
        runBlocking {
            val events = (1..5).associate { "obs-$it" to event("obs-$it").copy(id = it.toLong(), timestamp = it.toLong()) }
            val fires = mapOf("hot" to (1..5).map { ItemRuleEntity("obs-$it", "hot", 500L + it) })
            val (repo, _) = repository(rules = listOf(rule("hot", "Hot day")), fires = fires, events = events)

            val first = repo.alerts(cursorTs = null, cursorId = 0L, limit = 2)
            assertEquals(listOf("obs-5", "obs-4"), first.alerts.map { it.itemId })
            assertTrue(first.hasMore)

            val second = repo.alerts(first.nextTs!!, first.nextId!!, limit = 2)
            assertEquals(listOf("obs-3", "obs-2"), second.alerts.map { it.itemId })
            assertTrue(second.hasMore)

            val third = repo.alerts(second.nextTs!!, second.nextId!!, limit = 2)
            assertEquals(listOf("obs-1"), third.alerts.map { it.itemId })
            assertTrue(!third.hasMore)
        }

    @Test
    fun `the alert count is matched events, not fires`() =
        runBlocking {
            val (repo, _) =
                repository(
                    rules = listOf(rule("hot", "Hot day"), rule("cold", "Cold day")),
                    fires =
                        mapOf(
                            "hot" to listOf(ItemRuleEntity("obs-1", "hot", 500L), ItemRuleEntity("obs-2", "hot", 600L)),
                            "cold" to listOf(ItemRuleEntity("obs-1", "cold", 501L)),
                        ),
                    events =
                        mapOf(
                            "obs-1" to event("obs-1").copy(id = 1),
                            "obs-2" to event("obs-2").copy(id = 2),
                        ),
                )
            assertEquals("two events, three fires", 2, repo.observeAlertCount().first())
        }
}
