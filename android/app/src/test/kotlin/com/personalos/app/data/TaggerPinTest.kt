package com.personalos.app.data

import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.RuleEvaluator
import com.personalos.app.core.rules.RuleItem
import com.personalos.app.core.tag.TagGroups
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.TagResult
import com.personalos.app.core.tag.Tagger
import com.personalos.app.core.tag.TaggerKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaggerPinTest {
    private class ThrowingTagger : Tagger {
        override val id: String = "throw-v1"
        override val kind: TaggerKind = TaggerKind.HEURISTIC
        override val version: Int = 1

        override suspend fun tag(input: TagInput): TagResult = throw IllegalStateException("boom")
    }

    private class FakeTagDao : ItemTagDao {
        val rows = mutableListOf<ItemTagEntity>()

        override suspend fun tagsForItems(itemIds: List<String>): List<ItemTagRow> = rows.filter { it.itemId in itemIds }.map { ItemTagRow(it.itemId, it.tag) }

        override suspend fun insertAll(tags: List<ItemTagEntity>): List<Long> {
            rows += tags
            return tags.map { 1L }
        }

        override suspend fun activeTagger(): TaggerEntity? = null

        override suspend fun deactivateAll() = Unit

        override suspend fun register(tagger: TaggerEntity) = Unit

        override suspend fun countForTagger(taggerId: String): Int = rows.count { it.taggerId == taggerId }

        override fun observeTagCounts(): kotlinx.coroutines.flow.Flow<List<TagCount>> = kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun itemsForTag(
            tag: String,
            taggerId: String,
            limit: Int,
        ): List<String> = emptyList()

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    @Test
    fun `the current view reads the active tagger only`() {
        assertTrue(Sql.ITEM_TAGS_CURRENT.contains("active = 1"))
    }

    @Test
    fun `a throwing tagger writes nothing and never blocks`() =
        runBlocking {
            val dao = FakeTagDao()
            val writer = TagWriter(dao, ThrowingTagger())
            val tagged = writer.writeAll(listOf("item-1" to TagInput(text = "hello", source = com.personalos.app.core.tag.Transport.RSS)))
            assertEquals(0, tagged)
            assertTrue(dao.rows.isEmpty())
        }

    @Test
    fun `subject vocabulary is data checked at write`() {
        // `cyclone` is not in TagGroups.SUBJECTS, so parse rejects at write.
        assertFalse("cyclone" in TagGroups.SUBJECTS)
        assertThrows(IllegalArgumentException::class.java) {
            ConditionJson.parse("""{"subject": "cyclone"}""")
        }
        // A known subject matches by tag lookup.
        val item =
            RuleItem(
                id = "i",
                sourceId = "rss:x",
                title = "t",
                content = "c",
                tags = setOf("weather"),
                mentions = emptyList(),
            )
        assertTrue(RuleEvaluator.evaluate(item, ConditionJson.parse("""{"subject": "weather"}""")))
    }
}
