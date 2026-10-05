package com.personalos.app.data

import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.TagResult
import com.personalos.app.core.tag.Tagger
import com.personalos.app.core.tag.TaggerKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `item_tags_current` reads whichever tagger is marked active, so which one holds
 * the flag is a real decision, not bookkeeping. The rule under test: activation is
 * **promote-only**, so a worker whose container never upgraded cannot demote the
 * active tagger to the heuristic.
 */
class TagWriterActivationTest {
    private class Fake(
        override val id: String,
        override val kind: TaggerKind,
        override val version: Int = 1,
    ) : Tagger {
        override suspend fun tag(input: TagInput): TagResult = TagResult(tags = emptySet(), taggerId = id)
    }

    private class Dao : ItemTagDao {
        var active: TaggerEntity? = null

        override suspend fun activeTagger(): TaggerEntity? = active

        override suspend fun deactivateAll() {
            active = null
        }

        override suspend fun register(tagger: TaggerEntity) {
            active = tagger
        }

        override suspend fun insertAll(tags: List<ItemTagEntity>): List<Long> = tags.map { 1L }

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

    @Test
    fun `a weaker tagger does not demote a stronger active one`() =
        runBlocking {
            val dao = Dao()
            TagWriter(dao, Fake("cascade-v1", TaggerKind.CASCADE)).ensureActive()
            TagWriter(dao, Fake("heuristic-v10", TaggerKind.HEURISTIC)).ensureActive()

            assertEquals("cascade-v1", dao.active?.id)
        }

    @Test
    fun `a stronger tagger promotes over a weaker active one`() =
        runBlocking {
            val dao = Dao()
            TagWriter(dao, Fake("heuristic-v10", TaggerKind.HEURISTIC)).ensureActive()
            TagWriter(dao, Fake("cascade-v1", TaggerKind.CASCADE)).ensureActive()

            assertEquals("cascade-v1", dao.active?.id)
        }

    @Test
    fun `a newer version of the same rung supersedes the older`() =
        runBlocking {
            val dao = Dao()
            TagWriter(dao, Fake("cascade-v1", TaggerKind.CASCADE, version = 1)).ensureActive()
            TagWriter(dao, Fake("cascade-v2", TaggerKind.CASCADE, version = 2)).ensureActive()

            assertEquals("cascade-v2", dao.active?.id)
        }

    @Test
    fun `an older version of the same rung does not regress the active one`() =
        runBlocking {
            val dao = Dao()
            TagWriter(dao, Fake("cascade-v2", TaggerKind.CASCADE, version = 2)).ensureActive()
            TagWriter(dao, Fake("cascade-v1", TaggerKind.CASCADE, version = 1)).ensureActive()

            assertEquals("cascade-v2", dao.active?.id)
        }

    @Test
    fun `the first tagger registers when nothing is active`() =
        runBlocking {
            val dao = Dao()
            TagWriter(dao, Fake("heuristic-v10", TaggerKind.HEURISTIC)).ensureActive()

            assertEquals("heuristic-v10", dao.active?.id)
        }
}
