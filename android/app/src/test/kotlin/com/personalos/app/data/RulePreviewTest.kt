package com.personalos.app.data

import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.tag.Tags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview is the SQL half of the dry run (ADR §12): it loads a bounded,
 * optionally narrowed candidate set and hands it to [RuleWriter.dryRun], which
 * evaluates and writes nothing.
 *
 * The DAO fakes below model the contract [Sql.EVENTS_RULE_PREVIEW_CANDIDATES]
 * implements - window, optional filter, newest-first, cap. The statement itself
 * is run against real SQLite in [RulePreviewSqlTest]; here the fakes let the
 * loader and previewer be exercised as a unit.
 */
class RulePreviewTest {
    // ------------------------------------------------------------------ fakes

    private data class PreviewArgs(
        val since: Long,
        val sourceId: String?,
        val tag: String?,
        val mentionKind: String?,
        val mentionValue: String?,
        val limit: Int,
    )

    private open class StubEventDao : EventDao {
        override suspend fun insertAll(events: List<EventEntity>): List<Long> = events.map { 1L }

        override suspend fun insert(event: EventEntity): Long = 1L

        override fun observeCount(): Flow<Int> = flowOf(0)

        override fun observeCountSince(
            type: String,
            since: Long,
        ): Flow<Int> = flowOf(0)

        override fun observeSourceCount(): Flow<Int> = flowOf(0)

        override fun observeCountAllSince(since: Long): Flow<Int> = flowOf(0)

        override fun observeFeedSourceCount(): Flow<Int> = flowOf(0)

        override fun observeCountByMode(mode: String): Flow<Int> = flowOf(0)

        override suspend fun getCount(): Int = 0

        override fun observeMaxId(): Flow<Long?> = flowOf(null)

        override suspend fun page(
            mode: String,
            cursorTs: Long?,
            cursorId: Long,
            limit: Int,
        ): List<EventEntity> = emptyList()

        override suspend fun deleteAll() = Unit

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
    }

    /** Models the preview query's window, optional filters, ordering and cap. */
    private class FakeEventDao(
        val rows: MutableList<RulePreviewCandidate> = mutableListOf(),
        val tags: Map<String, Set<String>> = emptyMap(),
        val mentions: Map<String, List<Pair<String, String>>> = emptyMap(),
    ) : StubEventDao() {
        var lastArgs: PreviewArgs? = null
            private set

        override suspend fun rulePreviewCandidates(
            since: Long,
            sourceId: String?,
            tag: String?,
            mentionKind: String?,
            mentionValue: String?,
            limit: Int,
        ): List<RulePreviewCandidate> {
            lastArgs = PreviewArgs(since, sourceId, tag, mentionKind, mentionValue, limit)
            return rows
                .asSequence()
                .filter { it.timestamp >= since }
                .filter { sourceId == null || it.sourceId == sourceId }
                .filter { tag == null || tag in tags[it.itemId].orEmpty() }
                .filter { mentionKind == null || mentions[it.itemId].orEmpty().any { (k, v) -> k == mentionKind && v == mentionValue } }
                .sortedByDescending { it.timestamp }
                .take(limit)
                .toList()
        }
    }

    private open class StubRuleDao : RuleDao {
        override fun observeAll(): Flow<List<RuleEntity>> = flowOf(emptyList())

        override suspend fun all(): List<RuleEntity> = emptyList()

        override suspend fun count(): Int = 0

        override suspend fun insertAll(rules: List<RuleEntity>): List<Long> = rules.map { 1L }

        override suspend fun updateUserOnly(
            id: String,
            name: String,
            conditionJson: String,
            actionJson: String,
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

    private class FakeItemRuleDao(
        val rows: MutableList<ItemRuleEntity> = mutableListOf(),
        var insertCalls: Int = 0,
    ) : ItemRuleDao {
        override fun observeAll(): Flow<List<ItemRuleEntity>> = flowOf(rows.toList())

        override suspend fun all(): List<ItemRuleEntity> = rows.toList()

        override suspend fun insertAll(matches: List<ItemRuleEntity>): List<Long> {
            insertCalls++
            return matches.map { match ->
                if (rows.any { it.itemId == match.itemId && it.ruleId == match.ruleId }) {
                    -1L
                } else {
                    rows += match
                    1L
                }
            }
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

    private open class StubItemFieldDao : ItemFieldDao {
        override suspend fun insertAll(fields: List<ItemFieldEntity>): List<Long> = fields.map { 1L }

        override suspend fun forItem(itemId: String): List<ItemFieldEntity> = emptyList()

        override suspend fun forItems(itemIds: List<String>): List<ItemFieldEntity> = emptyList()
    }

    private fun previewer(
        eventDao: EventDao,
        ruleDao: RuleDao = StubRuleDao(),
        matchDao: ItemRuleDao = FakeItemRuleDao(),
        tagDao: ItemTagDao = StubItemTagDao(),
        mentionDao: MentionDao = StubMentionDao(),
        fieldDao: ItemFieldDao = StubItemFieldDao(),
        scanCap: Int = RulePreviewer.DEFAULT_SCAN_CAP,
        sampleLimit: Int = RulePreviewer.DEFAULT_SAMPLE_LIMIT,
    ) = RulePreviewer(
        RulePreviewLoader(eventDao),
        RuleWriter(ruleDao, matchDao, tagDao, mentionDao, fieldDao),
        scanCap = scanCap,
        sampleLimit = sampleLimit,
    )

    private fun candidate(
        itemId: String,
        sourceId: String = "src-1",
        timestamp: Long = NOW,
    ) = RulePreviewCandidate(
        itemId = itemId,
        sourceId = sourceId,
        title = "title-$itemId",
        content = "content-$itemId",
        timestamp = timestamp,
    )

    // ----------------------------------------------------------------- window

    @Test
    fun `the window bound excludes items older than the window`() =
        runBlocking {
            val events =
                FakeEventDao(
                    mutableListOf(
                        candidate("recent", timestamp = NOW - DAY_MS),
                        candidate("old", timestamp = NOW - 8 * DAY_MS),
                    ),
                )

            val result = previewer(events).preview("""{"source":"src-1"}""", windowDays = 7, now = NOW)

            val available = result as RulePreview.Available
            assertEquals("only the in-window item is scanned", 1, available.scannedCount)
            assertEquals(1, available.matchedCount)
            assertEquals("the loader's bound is the window", NOW - 7 * DAY_MS, events.lastArgs!!.since)
            assertEquals(7, available.windowDays)
        }

    // --------------------------------------------------------------- narrowing

    @Test
    fun `a source-narrowed preview finds an item the un-narrowed cap would miss`() =
        runBlocking {
            val events =
                FakeEventDao(
                    mutableListOf(
                        // Ten newer items from another source, then the one the
                        // rule actually wants.
                        *(
                            (1..10)
                                .map { candidate("other-$it", sourceId = "rss:other", timestamp = NOW - it * 60_000L) }
                                .toTypedArray()
                        ),
                        candidate("bank", sourceId = "sms:bank", timestamp = NOW - 11 * 60_000L),
                    ),
                )
            val narrow = previewer(events, scanCap = 3)

            // Without an indexable predicate the cap stops before the match.
            val control = narrow.preview("""{"text":{"pattern":"nomatch","target":"any"}}""", now = NOW) as RulePreview.Available
            assertEquals("the cap really would hide it", 0, control.matchedCount)
            assertEquals(3, control.scannedCount)

            // Source is indexed, so SQL reaches it before the cap applies.
            val narrowed = narrow.preview("""{"source":"sms:bank"}""", now = NOW) as RulePreview.Available
            assertEquals(listOf("bank"), narrowed.sample.map { it.itemId })
            assertEquals(1, narrowed.matchedCount)
        }

    // ------------------------------------------------------------------- zero

    @Test
    fun `a rule matching nothing returns zero rather than a false positive`() =
        runBlocking {
            val events = FakeEventDao(mutableListOf(candidate("a"), candidate("b", sourceId = "src-2")))

            val result = previewer(events).preview("""{"source":"missing"}""", now = NOW) as RulePreview.Available

            assertEquals(0, result.matchedCount)
            assertTrue(result.sample.isEmpty())
            assertFalse(result.truncated)
        }

    // ----------------------------------------------------------------- series

    @Test
    fun `a series condition previews as unavailable instead of throwing`() =
        runBlocking {
            val previewer = previewer(FakeEventDao(mutableListOf(candidate("a"))))
            val conditions =
                listOf(
                    """{"crossing":{"field":"price","direction":"below","value":1,"window":5}}""",
                    """{"delta":{"field":"price","by":-5000,"window":7}}""",
                    """{"min":{"field":"price","op":"lt","value":1,"window":5}}""",
                    """{"max":{"field":"price","op":"gt","value":1,"window":5}}""",
                )

            conditions.forEach { condition ->
                // No exception escapes: the boundary catches the evaluator's loud refusal.
                val result = previewer.preview(condition, now = NOW)
                val unavailable = result as RulePreview.Unavailable
                assertEquals(
                    "a series condition is reported as such, never as a non-match",
                    UnavailableReason.SERIES_UNSUPPORTED,
                    unavailable.reason,
                )
            }
        }

    // -------------------------------------------------------------------- cap

    @Test
    fun `the scan cap is respected and reported as truncated`() =
        runBlocking {
            val events =
                FakeEventDao(
                    MutableList(10) { candidate("i$it", timestamp = NOW - it * 1_000L) },
                )

            val result =
                previewer(events, scanCap = 3, sampleLimit = 2)
                    .preview("""{"source":"src-1"}""", now = NOW) as RulePreview.Available

            assertEquals("only the cap is evaluated", 3, result.scannedCount)
            assertEquals(3, result.matchedCount)
            assertTrue(result.truncated)
            assertEquals("sample is capped by the caller's limit", 2, result.sample.size)
            assertEquals(3, result.scanCap)
            assertEquals("the loader asks for one extra to detect truncation", 4, events.lastArgs!!.limit)
        }

    @Test
    fun `an exactly-full candidate set is not reported as truncated`() =
        runBlocking {
            val events = FakeEventDao(MutableList(3) { candidate("i$it", timestamp = NOW - it * 1_000L) })

            val result = previewer(events, scanCap = 3).preview("""{"source":"src-1"}""", now = NOW) as RulePreview.Available

            assertFalse(result.truncated)
            assertEquals(3, result.scannedCount)
        }

    // --------------------------------------------------------- coarse filters

    @Test
    fun `coarse filters narrow on source, tag and mention and leave text and field to the evaluator`() =
        runBlocking {
            val events = FakeEventDao()
            val loader = RulePreviewLoader(events)

            loader.candidates(
                ConditionJson.parse(
                    """
                    {"all":[
                      {"source":"src-1"},
                      {"subject":"travel"},
                      {"mention":{"kind":"place","value":"Kolkata"}},
                      {"text":{"pattern":"bandh","target":"any"}},
                      {"field":{"name":"amount","op":"gt","value":1}}
                    ]}
                    """.trimIndent(),
                ),
                since = 0,
                scanCap = 10,
            )

            assertEquals("src-1", events.lastArgs!!.sourceId)
            assertEquals("travel", events.lastArgs!!.tag)
            assertEquals("place", events.lastArgs!!.mentionKind)
            assertEquals("Kolkata", events.lastArgs!!.mentionValue)
        }

    @Test
    fun `a marker predicate narrows on the marker tag`() =
        runBlocking {
            val events = FakeEventDao()

            RulePreviewLoader(events).candidates(ConditionJson.parse("""{"marker":true}"""), since = 0, scanCap = 10)

            assertEquals("news", events.lastArgs!!.tag)
        }

    @Test
    fun `an any-of-sources predicate does not narrow, because one branch need not hold`() =
        runBlocking {
            val events = FakeEventDao()

            RulePreviewLoader(events).candidates(
                ConditionJson.parse("""{"any":[{"source":"a"},{"source":"b"}]}"""),
                since = 0,
                scanCap = 10,
            )

            assertNull(events.lastArgs!!.sourceId)
        }

    @Test
    fun `a text-only condition narrows on nothing`() =
        runBlocking {
            val events = FakeEventDao()

            RulePreviewLoader(events).candidates(
                ConditionJson.parse("""{"text":{"pattern":"bandh","target":"any"}}"""),
                since = 0,
                scanCap = 10,
            )

            assertNull(events.lastArgs!!.sourceId)
            assertNull(events.lastArgs!!.tag)
            assertNull(events.lastArgs!!.mentionKind)
        }

    // ------------------------------------------------------------ writes nothing

    @Test
    fun `a preview writes nothing to any table`() =
        runBlocking {
            val tags = mapOf("i1" to setOf(Tags.TRAVEL))
            val events = FakeEventDao(mutableListOf(candidate("i1"), candidate("i2", sourceId = "src-2")), tags = tags)
            val mentions =
                mapOf(
                    "i1" to
                        listOf(
                            MentionEntity(
                                itemId = "i1",
                                kind = "place",
                                surface = "Kolkata",
                                entityId = null,
                                confidence = 1f,
                                mentionedAt = 1L,
                            ),
                        ),
                )
            val fields = mapOf("i1" to listOf(ItemFieldEntity(itemId = "i1", name = "amount", valueNum = 12_000.0)))
            val matchDao = FakeItemRuleDao()
            val previewer =
                previewer(
                    eventDao = events,
                    matchDao = matchDao,
                    tagDao = TagsByItem(tags.mapValues { it.value.toList() }),
                    mentionDao = MentionsByItem(mentions),
                    fieldDao = FieldsByItem(fields),
                )

            // Snapshot every relevant table's contents before the preview.
            val eventsBefore = events.rows.toList()
            val tagsBefore = tags.toMap()
            val mentionsBefore = mentions.toMap()
            val fieldsBefore = fields.toMap()
            val matchesBefore = matchDao.rows.toList()

            // A rule that matches, so evaluation genuinely runs.
            val result = previewer.preview("""{"all":[{"subject":"travel"},{"field":{"name":"amount","op":"gt","value":10000}}]}""", now = NOW)

            assertEquals(1, (result as RulePreview.Available).matchedCount)
            assertEquals("events unchanged", eventsBefore, events.rows.toList())
            assertEquals("item_tags unchanged", tagsBefore, tags)
            assertEquals("mentions unchanged", mentionsBefore, mentions)
            assertEquals("item_fields unchanged", fieldsBefore, fields)
            assertEquals("item_rules unchanged", matchesBefore, matchDao.rows.toList())
            assertEquals("no match insert attempted", 0, matchDao.insertCalls)
        }

    // ------------------------------------------------------------- test doubles

    private class TagsByItem(
        private val tags: Map<String, List<String>>,
    ) : StubItemTagDao() {
        override suspend fun tagsForItems(itemIds: List<String>): List<ItemTagRow> = itemIds.flatMap { id -> tags[id].orEmpty().map { ItemTagRow(id, it) } }
    }

    private class MentionsByItem(
        private val mentions: Map<String, List<MentionEntity>>,
    ) : StubMentionDao() {
        override suspend fun forItems(itemIds: List<String>): List<MentionEntity> = itemIds.flatMap { mentions[it].orEmpty() }
    }

    private class FieldsByItem(
        private val fields: Map<String, List<ItemFieldEntity>>,
    ) : StubItemFieldDao() {
        override suspend fun forItems(itemIds: List<String>): List<ItemFieldEntity> = itemIds.flatMap { fields[it].orEmpty() }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val NOW = 1_700_000_000_000L
    }
}
