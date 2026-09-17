package com.personalos.app.data

import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.TagResult
import com.personalos.app.core.tag.Tagger
import com.personalos.app.core.tag.TaggerKind
import com.personalos.app.data.adapters.ObservationWriter
import com.personalos.app.data.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeIngestTest {
    private class FakeEventDao : EventDao {
        val rows = mutableListOf<EventEntity>()
        var seq = 1L

        override suspend fun insertAll(events: List<EventEntity>): List<Long> =
            events.map { row ->
                if (rows.any { it.dedupeKey == row.dedupeKey }) {
                    -1L
                } else {
                    rows += row.copy(id = seq++)
                    1L
                }
            }

        override suspend fun insert(event: EventEntity): Long {
            rows += event.copy(id = seq++)
            return seq
        }

        override fun observeCount(): Flow<Int> = flowOf(rows.size)

        override fun observeCountSince(
            type: String,
            since: Long,
        ): Flow<Int> = flowOf(0)

        override fun observeSourceCount(): Flow<Int> = flowOf(0)

        override fun observeCountAllSince(since: Long): Flow<Int> = flowOf(0)

        override fun observeFeedSourceCount(): Flow<Int> = flowOf(0)

        override fun observeCountByMode(mode: String): Flow<Int> = flowOf(0)

        override suspend fun getCount(): Int = rows.size

        override fun observeMaxId(): Flow<Long?> = flowOf(rows.maxOfOrNull { it.id })

        override suspend fun page(
            mode: String,
            cursorTs: Long?,
            cursorId: Long,
            limit: Int,
        ): List<EventEntity> = emptyList()

        override suspend fun deleteAll() {
            rows.clear()
        }

        override fun observeCountByCategory(category: String?): Flow<Int> = flowOf(0)

        override fun observeSourceCounts(): Flow<List<SourceCount>> = flowOf(emptyList())

        override suspend fun itemsAfter(
            afterId: Long,
            limit: Int,
        ): List<RetagCandidate> = emptyList()

        override suspend fun radarPage(
            category: String?,
            cursorTs: Long?,
            cursorId: Long,
            limit: Int,
        ): List<EventEntity> = emptyList()

        override suspend fun rulePreviewCandidates(
            since: Long,
            sourceId: String?,
            tag: String?,
            mentionKind: String?,
            mentionValue: String?,
            limit: Int,
        ): List<RulePreviewCandidate> = emptyList()

        override suspend fun pageByTag(
            tag: String,
            cursorTs: Long?,
            cursorId: Long,
            limit: Int,
        ): List<TaggedEvent> = emptyList()

        override fun observeCountByTag(tag: String): Flow<Int> = flowOf(0)

        override suspend fun dayHeadersByTag(tag: String): List<DayHeader> = emptyList()

        override suspend fun pageByTagDay(
            tag: String,
            dayStart: Long,
            dayEnd: Long,
            cursorTs: Long?,
            cursorId: Long,
            limit: Int,
        ): List<TaggedEvent> = emptyList()

        override suspend fun eventById(id: Long): TaggedEvent? = null

        override suspend fun enrichmentCandidates(limit: Int): List<EnrichmentCandidate> = emptyList()

        override suspend fun markEnrichmentAttempted(
            id: Long,
            enrichedAt: Long,
        ) = Unit

        override suspend fun updateEnrichedContent(
            id: Long,
            content: String,
            enrichedAt: Long,
        ) = Unit

        override suspend fun byDedupeKey(dedupeKey: String): EventEntity? = rows.singleOrNull { it.dedupeKey == dedupeKey }

        override suspend fun updateObservation(
            ulid: String,
            timestamp: Long,
            title: String,
            content: String,
        ) {
            val index = rows.indexOfFirst { it.ulid == ulid }
            if (index >= 0) rows[index] = rows[index].copy(timestamp = timestamp, title = title, content = content)
        }

        override suspend fun latestObservation(sourceId: String): EventEntity? = rows.filter { it.source == sourceId && it.type == "observation" }.maxByOrNull { it.timestamp }

        override fun observeLatestObservation(sourceId: String): Flow<EventEntity?> = flowOf(latestObservationSync(sourceId))

        private fun latestObservationSync(sourceId: String): EventEntity? = rows.filter { it.source == sourceId && it.type == "observation" }.maxByOrNull { it.timestamp }

        override suspend fun latestObservations(sourceIds: List<String>): List<EventEntity> = rows.filter { it.source in sourceIds && it.type == "observation" }
    }

    private class FakeFieldDao : ItemFieldDao {
        val rows = mutableListOf<ItemFieldEntity>()

        override suspend fun insertAll(fields: List<ItemFieldEntity>): List<Long> =
            fields.map { row ->
                if (rows.any { it.itemId == row.itemId && it.name == row.name }) {
                    -1L
                } else {
                    rows += row
                    1L
                }
            }

        override suspend fun forItem(itemId: String): List<ItemFieldEntity> = rows.filter { it.itemId == itemId }

        override suspend fun forItems(itemIds: List<String>): List<ItemFieldEntity> = rows.filter { it.itemId in itemIds }

        override suspend fun replaceAll(fields: List<ItemFieldEntity>) {
            for (row in fields) {
                rows.removeIf { it.itemId == row.itemId && it.name == row.name }
                rows += row
            }
        }

        override suspend fun deleteForItem(itemId: String) {
            rows.removeIf { it.itemId == itemId }
        }

        override suspend fun seriesWindow(
            sourceId: String,
            field: String,
            limit: Int,
        ): List<SeriesSample> = emptyList()
    }

    private class FixedTagger : Tagger {
        override val id: String = "fixed-v1"
        override val kind: TaggerKind = TaggerKind.HEURISTIC
        override val version: Int = 1

        override suspend fun tag(input: TagInput): TagResult = TagResult(tags = setOf("weather"))
    }

    private class StubTagDao : ItemTagDao {
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

    private class StubRuleDao : RuleDao {
        override fun observeAll(): Flow<List<RuleEntity>> = flowOf(emptyList())

        override suspend fun all(): List<RuleEntity> = emptyList()

        override suspend fun count(): Int = 0

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

    private class StubMatchDao : ItemRuleDao {
        override fun observeAll(): Flow<List<ItemRuleEntity>> = flowOf(emptyList())

        override suspend fun all(): List<ItemRuleEntity> = emptyList()

        override suspend fun insertAll(matches: List<ItemRuleEntity>): List<Long> = matches.map { 1L }
    }

    private class StubMentionDao : MentionDao {
        override suspend fun insertAll(mentions: List<MentionEntity>): List<Long> = mentions.map { 1L }

        override suspend fun forItem(itemId: String): List<MentionEntity> = emptyList()

        override suspend fun forItems(itemIds: List<String>): List<MentionEntity> = emptyList()

        override suspend fun missingItems(
            afterId: Long,
            limit: Int,
        ): List<MentionCandidate> = emptyList()

        override suspend fun deleteSurfaces(surfaces: List<String>): Int = 0
    }

    @Test
    fun `same bucket keeps one row and the latest value`() =
        runBlocking {
            val events = FakeEventDao()
            val fields = FakeFieldDao()
            val writer =
                ObservationWriter(
                    events,
                    fields,
                    TagWriter(StubTagDao(), FixedTagger()),
                    RuleWriter(StubRuleDao(), StubMatchDao(), StubTagDao(), StubMentionDao(), fields),
                    bucketMs = 3_600_000L,
                )
            val firstHour = 1_726_531_200_000L
            val first =
                ObservationWriter.Observation(
                    identity = "weather:bengaluru",
                    timestamp = firstHour + 100_000L,
                    title = "Bengaluru",
                    content = "31.0C",
                    topicTag = "weather",
                    fields = mapOf("temp_c" to FieldValue.Num(31.0)),
                )
            val ulid1 = writer.write(first, now = firstHour)
            val ulid2 =
                writer.write(first.copy(timestamp = firstHour + 200_000L, content = "31.2C", fields = mapOf("temp_c" to FieldValue.Num(31.2))), now = firstHour)
            assertEquals(ulid1, ulid2)
            assertEquals(1, events.rows.count { it.source == "weather:bengaluru" })
            val stored = fields.forItem(ulid1).single { it.name == "temp_c" }
            assertEquals(31.2, stored.valueNum!!, 0.0001)
        }

    @Test
    fun `counter duplicates freeze the first write`() =
        runBlocking {
            val fields = FakeFieldDao()
            val first = listOf(FieldValue.Num(12000.0).toEntity("item-1", "amount"))
            assertEquals(listOf(1L), fields.insertAll(first))
            // A revised counter value is a no-op under IGNORE.
            assertEquals(listOf(-1L), fields.insertAll(listOf(FieldValue.Num(99999.0).toEntity("item-1", "amount"))))
            assertEquals(12000.0, fields.forItem("item-1").single().valueNum!!, 0.0001)
        }

    @Test
    fun `gauge dispatch evaluates item and series paths`() =
        runBlocking {
            val matches = StubMatchDao()
            val fieldDao = FakeFieldDao()
            val writer = RuleWriter(StubRuleDaoWithRules(), matches, StubTagDao(), StubMentionDao(), fieldDao)
            fieldDao.replaceAll(listOf(FieldValue.Num(31.0).toEntity("obs-1", "temp_c")))
            val seed = RuleItemSeed(itemId = "obs-1", sourceId = "weather:bengaluru", title = "Bengaluru", content = "31.0C")
            val written =
                writer.writeGaugeSample(
                    seed = seed,
                    windowFor = { _, _ -> listOf(29.0, 31.0) },
                    now = 0L,
                )
            assertTrue(written >= 1)
        }

    private class StubRuleDaoWithRules : RuleDao {
        private val rules =
            listOf(
                RuleEntity(
                    id = "item-rule",
                    name = "hot",
                    enabled = true,
                    seeded = false,
                    conditionJson = """{"field": {"name": "temp_c", "op": "gt", "value": 30}}""",
                    actionJson = """{"delivery": "none", "position": 0}""",
                    position = 0L,
                    createdAt = 0L,
                    updatedAt = null,
                    color = null,
                ),
                RuleEntity(
                    id = "series-rule",
                    name = "warming",
                    enabled = true,
                    seeded = false,
                    conditionJson = """{"crossing": {"field": "temp_c", "direction": "above", "value": 30, "window": 2}}""",
                    actionJson = """{"delivery": "none", "position": 0}""",
                    position = 0L,
                    createdAt = 0L,
                    updatedAt = null,
                    color = null,
                ),
            )

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

    @Test
    fun `series window reads one source and field capped`() {
        assertTrue(Sql.SERIES_WINDOW.contains("e.source = :sourceId"))
        assertTrue(Sql.SERIES_WINDOW.contains("f.name = :field"))
        assertTrue(Sql.SERIES_WINDOW.contains("LIMIT :limit"))
    }
}
