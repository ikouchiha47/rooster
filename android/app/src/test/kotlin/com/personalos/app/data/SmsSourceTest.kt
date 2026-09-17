package com.personalos.app.data

import com.personalos.app.core.mention.ListPartySource
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.TagResult
import com.personalos.app.core.tag.Tagger
import com.personalos.app.core.tag.TaggerKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poll path, exercised against a plain list: a poll ingests exactly the
 * messages newer than the persisted high-water mark, the mark advances, and a
 * poll with nothing new adds nothing (so a background poll can never re-insert
 * the inbox).
 */
class SmsSourceTest {
    private class FakeSmsReader(
        var messages: List<SmsMessage>,
        private val accessible: Boolean = true,
    ) : SmsReader {
        override suspend fun messagesAfter(sinceId: Long): List<SmsMessage>? = if (accessible) messages.filter { it.id > sinceId } else null

        override fun observe(onChange: () -> Unit): AutoCloseable = AutoCloseable { }
    }

    private class FakeMark(
        var value: Long = 0L,
    ) : SmsSyncMark {
        var writes = 0
            private set

        override fun read(): Long = value

        override fun write(id: Long) {
            value = id
            writes++
        }
    }

    private class FakeEventDao : EventDao {
        val rows = mutableListOf<EventEntity>()
        private val keys = mutableSetOf<String>()

        override suspend fun insertAll(events: List<EventEntity>): List<Long> =
            events.map { event ->
                if (!keys.add(event.dedupeKey)) {
                    -1L
                } else {
                    rows += event
                    rows.size.toLong()
                }
            }

        override suspend fun insert(event: EventEntity): Long = insertAll(listOf(event)).single()

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

        override fun observeMaxId(): Flow<Long?> = flowOf(null)

        override suspend fun page(
            mode: String,
            cursorTs: Long?,
            cursorId: Long,
            limit: Int,
        ): List<EventEntity> = emptyList()

        override suspend fun deleteAll() {
            rows.clear()
            keys.clear()
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

        override suspend fun byDedupeKey(dedupeKey: String): EventEntity? = null

        override suspend fun updateObservation(
            ulid: String,
            timestamp: Long,
            title: String,
            content: String,
        ) = Unit

        override suspend fun latestObservation(sourceId: String): EventEntity? = null

        override fun observeLatestObservation(sourceId: String): kotlinx.coroutines.flow.Flow<EventEntity?> = kotlinx.coroutines.flow.flowOf(null)

        override suspend fun latestObservations(sourceIds: List<String>): List<EventEntity> = emptyList()

        override suspend fun observationsByPrefix(prefix: String): List<EventEntity> = emptyList()
    }

    private class FakeTagger : Tagger {
        override val id = "fake-v1"
        override val kind = TaggerKind.REGEX
        override val version = 1

        override suspend fun tag(input: TagInput): TagResult = TagResult(tags = input.declaredTags, taggerId = id)
    }

    private class FakeItemTagDao : ItemTagDao {
        private val rows = mutableListOf<ItemTagEntity>()
        private var active: TaggerEntity? = null

        override suspend fun insertAll(tags: List<ItemTagEntity>): List<Long> {
            rows += tags
            return tags.map { 1L }
        }

        override suspend fun register(tagger: TaggerEntity) {
            active = tagger
        }

        override suspend fun deactivateAll() {
            active = null
        }

        override suspend fun activeTagger(): TaggerEntity? = active

        override suspend fun countForTagger(taggerId: String): Int = rows.count { it.taggerId == taggerId }

        override fun observeTagCounts(): Flow<List<TagCount>> = flowOf(emptyList())

        override suspend fun itemsForTag(
            tag: String,
            taggerId: String,
            limit: Int,
        ): List<String> = emptyList()

        override suspend fun deleteAll() {
            rows.clear()
        }

        override suspend fun tagsForItems(itemIds: List<String>): List<ItemTagRow> = emptyList()
    }

    private class FakeRuleDao : RuleDao {
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

    private class FakeItemRuleDao : ItemRuleDao {
        override fun observeAll(): Flow<List<ItemRuleEntity>> = flowOf(emptyList())

        override suspend fun all(): List<ItemRuleEntity> = emptyList()

        override suspend fun insertAll(matches: List<ItemRuleEntity>): List<Long> = matches.map { 1L }
    }

    private class FakeItemFieldDao : ItemFieldDao {
        override suspend fun insertAll(fields: List<ItemFieldEntity>): List<Long> = fields.map { 1L }

        override suspend fun forItem(itemId: String): List<ItemFieldEntity> = emptyList()

        override suspend fun forItems(itemIds: List<String>): List<ItemFieldEntity> = emptyList()

        override suspend fun replaceAll(fields: List<ItemFieldEntity>) = Unit

        override suspend fun deleteForItem(itemId: String) = Unit

        override suspend fun seriesWindow(
            sourceId: String,
            field: String,
            limit: Int,
        ): List<SeriesSample> = emptyList()
    }

    private class FakeMentionDao : MentionDao {
        override suspend fun insertAll(mentions: List<MentionEntity>): List<Long> = mentions.map { 1L }

        override suspend fun forItem(itemId: String): List<MentionEntity> = emptyList()

        override suspend fun forItems(itemIds: List<String>): List<MentionEntity> = emptyList()

        override suspend fun missingItems(
            afterId: Long,
            limit: Int,
        ): List<MentionCandidate> = emptyList()

        override suspend fun deleteSurfaces(surfaces: List<String>): Int = 0
    }

    private fun source(
        reader: SmsReader,
        mark: SmsSyncMark = FakeMark(),
        events: FakeEventDao = FakeEventDao(),
    ): SmsSource =
        SmsSource(
            reader = reader,
            mark = mark,
            eventDao = events,
            tagWriter = TagWriter(FakeItemTagDao(), FakeTagger()),
            mentionWriter =
                MentionWriter(
                    FakeMentionDao(),
                    loadPlaces = { emptyList() },
                    loadParties = { emptyList() },
                    bundledParties = ListPartySource("test", emptyList()),
                ),
            fieldWriter = FieldWriter(FakeItemFieldDao()),
            ruleWriter =
                RuleWriter(
                    FakeRuleDao(),
                    FakeItemRuleDao(),
                    FakeItemTagDao(),
                    FakeMentionDao(),
                    FakeItemFieldDao(),
                ),
        )

    private fun message(id: Long): SmsMessage =
        SmsMessage(
            id = id,
            address = "+911234567890",
            body = "Rs.450 spent at BigBasket",
            date = 1_700_000_000_000L + id,
            type = 1,
        )

    @Test
    fun `a poll ingests the messages newer than the mark and advances it`() {
        val events = FakeEventDao()
        val mark = FakeMark()
        val source = source(FakeSmsReader(listOf(message(1), message(2))), mark, events)

        val added = runBlocking { source.ingestNewMessages() }

        assertEquals(2, added)
        assertEquals(listOf("sms:1", "sms:2"), events.rows.map { it.dedupeKey })
        assertEquals(2L, mark.value)
    }

    @Test
    fun `a poll with no new messages adds nothing and leaves the mark alone`() {
        val events = FakeEventDao()
        val mark = FakeMark()
        val source = source(FakeSmsReader(listOf(message(1), message(2))), mark, events)

        runBlocking { source.ingestNewMessages() }
        val writesAfterFirstPoll = mark.writes
        val secondPoll = runBlocking { source.ingestNewMessages() }

        assertEquals(0, secondPoll)
        assertEquals(2, events.rows.size)
        assertEquals(2L, mark.value)
        assertEquals(writesAfterFirstPoll, mark.writes)
    }

    @Test
    fun `a poll after new messages ingests exactly the newer ones`() {
        val events = FakeEventDao()
        val mark = FakeMark()
        val reader = FakeSmsReader(listOf(message(1), message(2)))
        val source = source(reader, mark, events)

        runBlocking { source.ingestNewMessages() }
        reader.messages = listOf(message(1), message(2), message(3), message(4))
        val added = runBlocking { source.ingestNewMessages() }

        assertEquals(2, added)
        assertEquals(listOf("sms:1", "sms:2", "sms:3", "sms:4"), events.rows.map { it.dedupeKey })
        assertEquals(4L, mark.value)
    }

    @Test
    fun `an empty table with a set mark re-ingests rather than staying dark`() {
        val events = FakeEventDao()
        val mark = FakeMark(value = 99L)
        val source = source(FakeSmsReader(listOf(message(1))), mark, events)

        val added = runBlocking { source.ingestNewMessages() }

        assertEquals(1, added)
        assertEquals(listOf("sms:1"), events.rows.map { it.dedupeKey })
        assertEquals(1L, mark.value)
    }

    @Test
    fun `an unreadable provider reports nothing rather than throwing`() {
        val source = source(FakeSmsReader(messages = emptyList(), accessible = false))

        val added = runBlocking { source.ingestNewMessages() }

        assertEquals(0, added)
    }

    @Test
    fun `the observer and the worker share the one SmsIngest entry point`() {
        assertTrue(SmsIngest::class.java.isAssignableFrom(SmsSource::class.java))
    }
}
