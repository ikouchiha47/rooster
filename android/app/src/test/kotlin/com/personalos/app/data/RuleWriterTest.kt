package com.personalos.app.data

import com.personalos.app.core.rules.SeriesPredicateUnsupportedException
import com.personalos.app.core.tag.Tags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleWriterTest {
    // ------------------------------------------------------------------- fakes

    private class FakeRuleDao(
        private val rules: List<RuleEntity>,
    ) : RuleDao {
        override fun observeAll(): Flow<List<RuleEntity>> = flowOf(rules.toList())

        override suspend fun all(): List<RuleEntity> = rules.toList()

        override suspend fun count(): Int = rules.size

        override suspend fun insertAll(rules: List<RuleEntity>): List<Long> = rules.map { 1L }

        // The writer never edits rules; these exist only to satisfy the DAO.
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

        override fun observeAll(): Flow<List<ItemRuleEntity>> = flowOf(rows.toList())

        override suspend fun all(): List<ItemRuleEntity> = rows.toList()

        /** Models Room's `INSERT OR IGNORE`: an existing (item, rule) pair is dropped. */
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

    private class TagsByItem(
        private val tags: Map<String, Set<String>>,
    ) : StubItemTagDao() {
        override suspend fun tagsForItems(itemIds: List<String>): List<ItemTagRow> = itemIds.flatMap { id -> tags[id].orEmpty().map { ItemTagRow(id, it) } }
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

    private class MentionsByItem(
        private val mentions: Map<String, List<MentionEntity>>,
    ) : StubMentionDao() {
        override suspend fun forItems(itemIds: List<String>): List<MentionEntity> = itemIds.flatMap { mentions[it].orEmpty() }
    }

    private open class StubItemFieldDao : ItemFieldDao {
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

    private class FieldsByItem(
        private val fields: Map<String, List<ItemFieldEntity>>,
    ) : StubItemFieldDao() {
        override suspend fun forItems(itemIds: List<String>): List<ItemFieldEntity> = itemIds.flatMap { fields[it].orEmpty() }
    }

    private fun writer(
        ruleDao: RuleDao,
        matchDao: ItemRuleDao,
        tags: Map<String, Set<String>> = emptyMap(),
        mentions: Map<String, List<MentionEntity>> = emptyMap(),
        fields: Map<String, List<ItemFieldEntity>> = emptyMap(),
    ) = RuleWriter(ruleDao, matchDao, TagsByItem(tags), MentionsByItem(mentions), FieldsByItem(fields))

    private fun rule(
        id: String,
        condition: String,
        enabled: Boolean = true,
    ) = RuleEntity(
        id = id,
        name = id,
        enabled = enabled,
        seeded = false,
        conditionJson = condition,
        actionJson = """{"delivery": "none", "position": 0}""",
        position = 0,
        createdAt = 1L,
    )

    private fun seed(
        itemId: String,
        sourceId: String = "src-1",
        title: String = "Kolkata metro opens",
        content: String = "",
    ) = RuleItemSeed(itemId = itemId, sourceId = sourceId, title = title, content = content)

    // ------------------------------------------------------------------- write

    @Test
    fun `writeAll matches only enabled rules and only where the condition holds`() =
        runBlocking {
            val rules =
                mutableListOf(
                    rule("non-match", """{"source": "other"}"""),
                    rule("match", """{"source": "src-1"}"""),
                    rule("disabled", """{"source": "src-1"}""", enabled = false),
                )
            val matches = FakeItemRuleDao()
            val writer = writer(FakeRuleDao(rules), matches)

            assertEquals(1, writer.writeAll(listOf(seed("i1"))))

            assertEquals(listOf("match"), matches.rows.map { it.ruleId })
            assertEquals("i1", matches.rows.single().itemId)
        }

    @Test
    fun `tags and mentions come from the store, not the seed`() =
        runBlocking {
            val rules =
                mutableListOf(
                    rule("subject", """{"subject": "travel"}"""),
                    rule("mention", """{"mention": {"kind": "place", "value": "Kolkata"}}"""),
                )
            val matches = FakeItemRuleDao()
            val writer =
                writer(
                    FakeRuleDao(rules),
                    matches,
                    tags = mapOf("i1" to setOf(Tags.TRAVEL)),
                    mentions =
                        mapOf(
                            "i1" to
                                listOf(
                                    MentionEntity(
                                        itemId = "i1",
                                        kind = "place",
                                        surface = "Kolkata",
                                        entityId = "1275004",
                                        confidence = 1f,
                                        mentionedAt = 1L,
                                    ),
                                ),
                        ),
                )

            assertEquals(2, writer.writeAll(listOf(seed("i1"))))
            assertEquals(setOf("subject", "mention"), matches.rows.map { it.ruleId }.toSet())
        }

    @Test
    fun `writing the same match twice leaves one row`() =
        runBlocking {
            val matches = FakeItemRuleDao()
            val writer = writer(FakeRuleDao(mutableListOf(rule("r1", """{"source": "src-1"}"""))), matches)

            assertEquals(1, writer.writeAll(listOf(seed("i1"))))
            assertEquals("the second write is ignored, not a new row", 0, writer.writeAll(listOf(seed("i1"))))

            assertEquals(1, matches.rows.size)
        }

    @Test
    fun `a series-predicate rule is skipped rather than throwing at ingest`() =
        runBlocking {
            val rules = mutableListOf(rule("monitor", """{"crossing": {"field": "price", "direction": "below", "value": 1, "window": 5}}"""))
            val matches = FakeItemRuleDao()
            val writer = writer(FakeRuleDao(rules), matches)

            assertEquals(0, writer.writeAll(listOf(seed("i1"))))
            assertTrue(matches.rows.isEmpty())
        }

    // -------------------------------------------------------------- enrichment

    @Test
    fun `enrichment re-evaluation considers only text rules and only the given items`() =
        runBlocking {
            val rules =
                mutableListOf(
                    rule("subject", """{"subject": "travel"}"""),
                    rule("text", """{"text": {"pattern": "bandh", "target": "any"}}"""),
                )
            val matches = FakeItemRuleDao()
            val writer =
                writer(
                    FakeRuleDao(rules),
                    matches,
                    tags = mapOf("i1" to setOf(Tags.TRAVEL), "i2" to setOf(Tags.TRAVEL)),
                )

            // Only i1 grew text that contains the pattern; the subject rule is
            // not enrichment-sensitive and must not be re-run at all.
            val written =
                writer.reevaluateEnriched(
                    listOf(
                        seed("i1", title = "Kolkata bandh called off"),
                        seed("i2", title = "Kolkata metro opens"),
                    ),
                )

            assertEquals(1, written)
            assertEquals(listOf("i1"), matches.rows.map { it.itemId })
            assertEquals(listOf("text"), matches.rows.map { it.ruleId })
        }

    // ------------------------------------------------------------------ dry run

    @Test
    fun `a dry run returns its matches and persists nothing`() =
        runBlocking {
            val matches = FakeItemRuleDao()
            val writer =
                writer(
                    FakeRuleDao(mutableListOf(rule("r1", """{"subject": "travel"}"""))),
                    matches,
                    tags = mapOf("i1" to setOf(Tags.TRAVEL), "i2" to setOf(Tags.GAMES)),
                )
            val seeds = listOf(seed("i1"), seed("i2"))

            // The write path does write, so the empty table after the dry run is meaningful.
            writer.writeAll(seeds)
            assertEquals("precondition: the writer does persist", 1, matches.rows.size)
            val rowsBefore = matches.rows.size
            val callsBefore = matches.insertCalls

            val result = writer.dryRun(rule("r1", """{"subject": "travel"}"""), seeds)

            assertEquals(listOf("i1"), result.matchedItemIds)
            assertEquals(1, result.count)
            assertEquals("no row written by a dry run", rowsBefore, matches.rows.size)
            assertEquals("no insert attempted by a dry run", callsBefore, matches.insertCalls)
        }

    @Test
    fun `a dry run refuses a series condition loudly`() =
        runBlocking {
            val writer = writer(FakeRuleDao(emptyList()), FakeItemRuleDao())
            val condition = """{"delta": {"field": "price", "by": -5000, "window": 7}}"""

            val error = runCatching { writer.dryRun(rule("monitor", condition), listOf(seed("i1"))) }.exceptionOrNull()

            assertTrue(
                "expected a loud refusal, got $error",
                error is SeriesPredicateUnsupportedException,
            )
        }

    @Test
    fun `stored fields reach the evaluator through an ingest seed`() =
        runBlocking {
            val condition = """{"field": {"name": "amount", "op": "gt", "value": 10000}}"""
            val matches = FakeItemRuleDao()
            val writer =
                writer(
                    FakeRuleDao(mutableListOf(rule("big", condition))),
                    matches,
                    // The value is stored (as the ingest path writes it); the seed
                    // carries only identity and text. The store is the owner.
                    fields =
                        mapOf(
                            "i1" to listOf(ItemFieldEntity(itemId = "i1", name = "amount", valueNum = 12000.0)),
                        ),
                )

            assertEquals(1, writer.writeAll(listOf(seed("i1"))))
            assertEquals(listOf("big"), matches.rows.map { it.ruleId })
        }

    @Test
    fun `a field predicate over an unstored name matches nothing`() =
        runBlocking {
            val condition = """{"field": {"name": "amount", "op": "gt", "value": 1}}"""
            val matches = FakeItemRuleDao()
            val writer = writer(FakeRuleDao(mutableListOf(rule("big", condition))), matches)

            assertEquals(0, writer.writeAll(listOf(seed("i1"))))
            assertTrue(matches.rows.isEmpty())
        }
}
