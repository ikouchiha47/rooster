package com.personalos.app.data.remote

import com.personalos.app.data.DayHeader
import com.personalos.app.data.EnrichmentCandidate
import com.personalos.app.data.EventDao
import com.personalos.app.data.EventEntity
import com.personalos.app.data.RetagCandidate
import com.personalos.app.data.RuleItemSeed
import com.personalos.app.data.RulePreviewCandidate
import com.personalos.app.data.SourceCount
import com.personalos.app.data.TaggedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enrichment hook (ADR §10, R3): only items whose text actually grew reach
 * the rule engine, so a failed fetch never triggers re-evaluation.
 */
class ArticleEnricherTest {
    private class FakeEventDao(
        private val candidates: List<EnrichmentCandidate>,
    ) : EventDao {
        val markedAttempted = mutableListOf<Long>()
        val updated = mutableListOf<Pair<Long, String>>()

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

        override suspend fun pageByTagItems(
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

        override suspend fun enrichmentCandidates(limit: Int): List<EnrichmentCandidate> = candidates

        override suspend fun markEnrichmentAttempted(
            id: Long,
            enrichedAt: Long,
        ) {
            markedAttempted += id
        }

        override suspend fun updateEnrichedContent(
            id: Long,
            content: String,
            enrichedAt: Long,
        ) {
            updated += id to content
        }

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

    private val summary = "OG".padEnd(120, 'o')
    private val goodPage = """<html><head><meta property="og:description" content="$summary"></head></html>"""
    private val emptyPage = """<html><head></head><body><p>hi</p></body></html>"""

    private fun candidate(
        id: Long,
        ulid: String,
        url: String,
    ) = EnrichmentCandidate(
        id = id,
        ulid = ulid,
        url = url,
        content = "",
        source = "the-hindu",
        title = "Kolkata metro opens",
    )

    @Test
    fun `only items whose text grew are handed to the rule engine`() =
        runBlocking {
            val dao =
                FakeEventDao(
                    listOf(
                        candidate(1, "u1", "https://example.com/a"),
                        candidate(2, "u2", "https://example.com/b"),
                    ),
                )
            val handed = mutableListOf<List<RuleItemSeed>>()
            val enricher =
                ArticleEnricher(
                    dao = dao,
                    fetch = { url -> if (url.endsWith("/a")) goodPage else emptyPage },
                    pauseMs = 0L,
                    onEnriched = { seeds -> handed += seeds },
                )

            assertEquals("one of the two candidates yielded a summary", 1, enricher.enrichBatch())
            assertEquals(listOf(1L), dao.updated.map { it.first })
            assertEquals(listOf(2L), dao.markedAttempted)

            val seeds = handed.single()
            assertEquals(listOf("u1"), seeds.map { it.itemId })
            assertEquals(summary, seeds.single().content)
            assertEquals("the-hindu", seeds.single().sourceId)
            assertTrue("the failed candidate never reaches the rule engine", seeds.none { it.itemId == "u2" })
        }

    @Test
    fun `an empty batch calls nothing`() =
        runBlocking {
            var called = false
            val enricher =
                ArticleEnricher(
                    dao = FakeEventDao(emptyList()),
                    fetch = { emptyPage },
                    pauseMs = 0L,
                    onEnriched = { called = true },
                )

            assertEquals(0, enricher.enrichBatch())
            assertTrue(!called)
        }
}
