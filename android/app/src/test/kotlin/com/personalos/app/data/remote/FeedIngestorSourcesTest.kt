package com.personalos.app.data.remote

import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.feed.FeedSource
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.TagResult
import com.personalos.app.core.tag.Tagger
import com.personalos.app.core.tag.TaggerKind
import com.personalos.app.core.tag.Transport
import com.personalos.app.data.DayHeader
import com.personalos.app.data.EnrichmentCandidate
import com.personalos.app.data.EventDao
import com.personalos.app.data.EventEntity
import com.personalos.app.data.ItemFieldDao
import com.personalos.app.data.ItemFieldEntity
import com.personalos.app.data.ItemRuleDao
import com.personalos.app.data.ItemRuleEntity
import com.personalos.app.data.ItemTagDao
import com.personalos.app.data.ItemTagEntity
import com.personalos.app.data.ItemTagRow
import com.personalos.app.data.MentionCandidate
import com.personalos.app.data.MentionDao
import com.personalos.app.data.MentionEntity
import com.personalos.app.data.MentionWriter
import com.personalos.app.data.PlaceEntity
import com.personalos.app.data.RetagCandidate
import com.personalos.app.data.RuleDao
import com.personalos.app.data.RuleEntity
import com.personalos.app.data.RulePreviewCandidate
import com.personalos.app.data.RuleWriter
import com.personalos.app.data.SeriesSample
import com.personalos.app.data.SourceCount
import com.personalos.app.data.SourceEntity
import com.personalos.app.data.TagCount
import com.personalos.app.data.TagWriter
import com.personalos.app.data.TaggedEvent
import com.personalos.app.data.TaggerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One hand fixture proves the whole source path: an enabled `search` source is
 * fetched as Google News RSS, its item lands with a `gnews:<slug>` source and
 * the source's tags, and the same item reaches the tag and mention writers.
 * Dedupe rides the existing `dedupe_key`: a second poll of the same link adds
 * nothing.
 */
class FeedIngestorSourcesTest {
    private class FakeEventDao : EventDao {
        val rows = mutableListOf<EventEntity>()
        private val keys = mutableSetOf<String>()
        private var nextId = 1L

        override suspend fun insertAll(events: List<EventEntity>): List<Long> =
            events.map { event ->
                if (!keys.add(event.dedupeKey)) {
                    -1L
                } else {
                    rows += event.copy(id = nextId)
                    nextId++
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
        val inputs = mutableListOf<TagInput>()

        override suspend fun tag(input: TagInput): TagResult {
            inputs += input
            return TagResult(tags = input.declaredTags, taggerId = id)
        }
    }

    private class FakeItemTagDao : ItemTagDao {
        val rows = mutableListOf<ItemTagEntity>()
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

        override suspend fun tagsForItems(itemIds: List<String>): List<ItemTagRow> = rows.filter { it.itemId in itemIds }.map { ItemTagRow(it.itemId, it.tag) }
    }

    private class FakeRuleDao(
        private val rules: List<RuleEntity> = emptyList(),
    ) : RuleDao {
        override fun observeAll(): Flow<List<RuleEntity>> = flowOf(rules)

        override suspend fun all(): List<RuleEntity> = rules

        override suspend fun count(): Int = rules.size

        override suspend fun insertAll(rules: List<RuleEntity>): List<Long> = rules.map { 1L }

        // Ingest only reads rules to evaluate them; these exist to satisfy the DAO.
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
        val rows = mutableListOf<ItemRuleEntity>()
        var insertCalls = 0

        override fun observeAll(): Flow<List<ItemRuleEntity>> = flowOf(rows)

        override suspend fun all(): List<ItemRuleEntity> = rows

        override suspend fun insertAll(matches: List<ItemRuleEntity>): List<Long> {
            insertCalls++
            return matches.map { match ->
                val known = rows.any { it.itemId == match.itemId && it.ruleId == match.ruleId }
                if (known) {
                    -1L
                } else {
                    rows += match
                    1L
                }
            }
        }
    }

    private class FakeItemFieldDao : ItemFieldDao {
        val rows = mutableListOf<ItemFieldEntity>()

        override suspend fun insertAll(fields: List<ItemFieldEntity>): List<Long> {
            rows += fields
            return fields.map { 1L }
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

    private class FakeMentionDao : MentionDao {
        val rows = mutableListOf<MentionEntity>()

        override suspend fun insertAll(mentions: List<MentionEntity>): List<Long> {
            rows += mentions
            return mentions.map { 1L }
        }

        override suspend fun forItem(itemId: String): List<MentionEntity> = rows.filter { it.itemId == itemId }

        override suspend fun forItems(itemIds: List<String>): List<MentionEntity> = rows.filter { it.itemId in itemIds }

        override suspend fun missingItems(
            afterId: Long,
            limit: Int,
        ): List<MentionCandidate> = emptyList()

        override suspend fun deleteSurfaces(surfaces: List<String>): Int {
            val lowered = surfaces.map { it.lowercase() }.toSet()
            val before = rows.size
            rows.removeAll { it.surface.lowercase() in lowered }
            return before - rows.size
        }
    }

    private class FakeCache : StringCache {
        val values = mutableMapOf<String, StringCache.Entry>()

        override fun read(key: String): StringCache.Entry? = values[key]

        override fun write(
            key: String,
            value: String,
            at: Long,
        ) {
            values[key] = StringCache.Entry(value, at)
        }
    }

    private val kolkata =
        PlaceEntity(
            id = "1275004",
            name = "Kolkata",
            ascii = "Kolkata",
            lat = 22.5726,
            lon = 88.3639,
            fclass = "P",
            fcode = "PPLA",
            admin1 = "28",
            population = 4631819L,
            alternates = "Calcutta|Kalkata",
        )

    private val source =
        SourceEntity(
            id = "source-1",
            name = "West Bengal",
            kind = "search",
            specJson =
                """{"query": "West Bengal", "query_lang_code": "en", "source_locale": "en-IN", "tags": ["news"]}""",
            seeded = true,
            enabled = true,
            createdAt = 1L,
        )

    /** A Google News-shaped item, `<source>` element included: the parser leaves it alone. */
    private val fixture =
        """
        <?xml version="1.0"?>
        <rss version="2.0"><channel>
          <title>West Bengal - Google News</title>
          <item>
            <title>Kolkata metro opens new stretch</title>
            <link>https://example.com/kolkata-metro</link>
            <description>CRS inspection passed for the Kolkata stretch.</description>
            <pubDate>Sat, 13 Sep 2026 09:31:00 +0530</pubDate>
            <source url="https://example.com">Example Daily</source>
          </item>
        </channel></rss>
        """.trimIndent()

    private fun ingestor(
        events: FakeEventDao,
        tags: FakeItemTagDao,
        mentions: FakeMentionDao,
        tagger: FakeTagger,
        sources: List<SourceEntity>,
        fetched: MutableList<String>,
        places: List<PlaceEntity> = listOf(kolkata),
        rules: RuleDao = FakeRuleDao(),
        matches: FakeItemRuleDao = FakeItemRuleDao(),
        fields: ItemFieldDao = FakeItemFieldDao(),
        cache: FakeCache = FakeCache(),
        feeds: List<FeedSource> = emptyList(),
    ): FeedIngestor =
        FeedIngestor(
            dao = events,
            cache = cache,
            tagWriter = TagWriter(tags, tagger),
            mentionWriter =
                MentionWriter(
                    mentions,
                    loadPlaces = { places },
                    loadParties = { emptyList() },
                ),
            ruleWriter = RuleWriter(rules, matches, tags, mentions, fields),
            feeds = feeds,
            loadSources = { sources },
            fetch = { url ->
                fetched += url
                fixture
            },
        )

    @Test
    fun `a gnews item lands tagged with the source and its tags`() =
        runBlocking {
            val events = FakeEventDao()
            val tags = FakeItemTagDao()
            val mentions = FakeMentionDao()
            val tagger = FakeTagger()
            val fetched = mutableListOf<String>()
            val ingestor = ingestor(events, tags, mentions, tagger, listOf(source), fetched)

            assertEquals(1, ingestor.refresh())

            val event = events.rows.single()
            assertEquals("gnews:west-bengal", event.source)
            assertEquals("https://example.com/kolkata-metro", event.dedupeKey)
            assertEquals("NEWS", event.category)

            assertEquals(
                "the source query hits the exact GNews template",
                listOf("https://news.google.com/rss/search?q=West+Bengal&hl=en-IN&gl=IN&ceid=IN:en"),
                fetched,
            )

            assertEquals("the source tags are what the tagger declares", 1, tagger.inputs.size)
            assertEquals(setOf("news"), tagger.inputs.single().declaredTags)
            assertEquals("West Bengal", tagger.inputs.single().sender)
            assertEquals(Transport.RSS, tagger.inputs.single().source)
            assertEquals(setOf("news"), tags.rows.map { it.tag }.toSet())

            assertTrue(
                "the same item reaches the mention writer",
                mentions.rows.any { it.itemId == event.ulid && it.surface == "Kolkata" },
            )
        }

    @Test
    fun `a repolled link dedupes on the existing key`() =
        runBlocking {
            val events = FakeEventDao()
            val ingestor =
                ingestor(
                    events,
                    FakeItemTagDao(),
                    FakeMentionDao(),
                    FakeTagger(),
                    listOf(source),
                    mutableListOf(),
                    places = emptyList(),
                )

            assertEquals(1, ingestor.refresh())
            assertEquals("the same link adds nothing the second time", 0, ingestor.refresh())
            assertEquals(1, events.rows.size)
        }

    @Test
    fun `disabled and non-search sources are never polled`() =
        runBlocking {
            val fetched = mutableListOf<String>()
            val ingestor =
                ingestor(
                    FakeEventDao(),
                    FakeItemTagDao(),
                    FakeMentionDao(),
                    FakeTagger(),
                    listOf(source.copy(id = "off", enabled = false), source.copy(id = "rss-1", kind = "rss")),
                    fetched,
                    places = emptyList(),
                )

            assertEquals(0, ingestor.refresh())
            assertTrue(fetched.isEmpty())
        }

    @Test
    fun `an item that lands is evaluated by enabled rules`() =
        runBlocking {
            val events = FakeEventDao()
            val matches = FakeItemRuleDao()
            val ingestor =
                ingestor(
                    events,
                    FakeItemTagDao(),
                    FakeMentionDao(),
                    FakeTagger(),
                    listOf(source),
                    mutableListOf(),
                    places = emptyList(),
                    rules = FakeRuleDao(listOf(rule("r1", """{"source": "gnews:west-bengal"}"""))),
                    matches = matches,
                )

            assertEquals(1, ingestor.refresh())

            assertEquals(1, matches.rows.size)
            assertEquals(events.rows.single().ulid, matches.rows.single().itemId)
            assertEquals("r1", matches.rows.single().ruleId)
        }

    @Test
    fun `a re-fetched item is not re-evaluated`() =
        runBlocking {
            val events = FakeEventDao()
            val matches = FakeItemRuleDao()
            val ingestor =
                ingestor(
                    events,
                    FakeItemTagDao(),
                    FakeMentionDao(),
                    FakeTagger(),
                    listOf(source),
                    mutableListOf(),
                    places = emptyList(),
                    rules = FakeRuleDao(listOf(rule("r1", """{"source": "gnews:west-bengal"}"""))),
                    matches = matches,
                )

            assertEquals(1, ingestor.refresh())
            assertEquals(0, ingestor.refresh())

            assertEquals("a deduped item does not run evaluation again", 1, matches.insertCalls)
        }

    private fun rule(
        id: String,
        condition: String,
    ) = RuleEntity(
        id = id,
        name = id,
        enabled = true,
        seeded = false,
        conditionJson = condition,
        actionJson = """{"delivery": "none", "position": 0}""",
        position = 0,
        createdAt = 1L,
    )

    /** A plain catalog feed, so the `feed:<id>` cache key is exercised too. */
    private val catalogFeed =
        FeedSource(
            id = "catalog-1",
            name = "Catalog",
            url = "https://example.com/feed",
            primaryTag = "news",
        )

    @Test
    fun `a fresh cached source payload is served without a fetch`() =
        runBlocking {
            val cache = FakeCache()
            cache.write("rule:${source.id}", fixture, System.currentTimeMillis())
            val events = FakeEventDao()
            val fetched = mutableListOf<String>()

            val ingestor =
                ingestor(
                    events,
                    FakeItemTagDao(),
                    FakeMentionDao(),
                    FakeTagger(),
                    listOf(source),
                    fetched,
                    cache = cache,
                    places = emptyList(),
                )

            assertEquals(1, ingestor.refresh())

            assertTrue("the cached body is ingested without a fetch", fetched.isEmpty())
            assertEquals(1, events.rows.size)
        }

    @Test
    fun `a forced refresh fetches despite a fresh cache entry`() =
        runBlocking {
            val cache = FakeCache()
            cache.write("rule:${source.id}", fixture, System.currentTimeMillis())
            val fetched = mutableListOf<String>()

            val ingestor =
                ingestor(
                    FakeEventDao(),
                    FakeItemTagDao(),
                    FakeMentionDao(),
                    FakeTagger(),
                    listOf(source),
                    fetched,
                    cache = cache,
                    places = emptyList(),
                )

            assertEquals(1, ingestor.refresh(force = true))

            assertEquals(
                listOf("https://news.google.com/rss/search?q=West+Bengal&hl=en-IN&gl=IN&ceid=IN:en"),
                fetched,
            )
        }

    @Test
    fun `the catalog feed gate still applies and force bypasses it`() =
        runBlocking {
            val cache = FakeCache()
            cache.write("feed:${catalogFeed.id}", fixture, System.currentTimeMillis())
            val fetched = mutableListOf<String>()

            ingestor(
                FakeEventDao(),
                FakeItemTagDao(),
                FakeMentionDao(),
                FakeTagger(),
                emptyList(),
                fetched,
                cache = cache,
                feeds = listOf(catalogFeed),
                places = emptyList(),
            ).refresh()
            assertTrue("a fresh catalog payload is not refetched", fetched.isEmpty())

            ingestor(
                FakeEventDao(),
                FakeItemTagDao(),
                FakeMentionDao(),
                FakeTagger(),
                emptyList(),
                fetched,
                cache = cache,
                feeds = listOf(catalogFeed),
                places = emptyList(),
            ).refresh(force = true)
            assertEquals("force fetches the still-fresh catalog payload", listOf(catalogFeed.url), fetched)
        }
}
