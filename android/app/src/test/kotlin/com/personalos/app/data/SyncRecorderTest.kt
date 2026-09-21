package com.personalos.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRecorderTest {
    private class FakeSyncRunDao : SyncRunDao {
        val rows = mutableListOf<SyncRunEntity>()
        var prunedBefore: Long? = null

        override suspend fun insert(run: SyncRunEntity): Long {
            rows += run.copy(id = (rows.size + 1).toLong())
            return rows.size.toLong()
        }

        override fun observeLatestPerSource(): Flow<List<SyncRunEntity>> = flowOf(latest())

        override suspend fun latestPerSource(): List<SyncRunEntity> = latest()

        override suspend fun runsForSource(
            sourceId: String,
            limit: Int,
        ): List<SyncRunEntity> = rows.filter { it.sourceId == sourceId }.sortedByDescending { it.finishedAt }.take(limit)

        override fun observeSourceCount(): Flow<Int> = flowOf(rows.map { it.sourceId }.toSet().size)

        override suspend fun pruneOlderThan(cutoffMs: Long): Int {
            prunedBefore = cutoffMs
            val before = rows.size
            rows.removeIf { it.finishedAt < cutoffMs }
            return before - rows.size
        }

        private fun latest(): List<SyncRunEntity> =
            rows
                .groupBy { it.sourceId }
                .mapValues { (_, group) -> group.maxBy { it.id } }
                .values
                .sortedByDescending { it.finishedAt }
    }

    @Test
    fun `a run is stored with its outcome and history pruned`() =
        runBlocking {
            val dao = FakeSyncRunDao()
            val recorder = RoomSyncRecorder(dao, now = { 1_000_000L })
            recorder.record(SyncRun("rss:x", "rss", 999_000L, 999_500L, true, 3, null))
            recorder.record(SyncRun("rss:x", "rss", 999_600L, 999_900L, false, 0, "HTTP 403"))
            // Latest per source is the failed run; history keeps both.
            assertEquals("HTTP 403", dao.latestPerSource().single().error)
            assertEquals(2, dao.runsForSource("rss:x", 10).size)
            // Prune horizon is now minus retention.
            assertEquals(1_000_000L - RoomSyncRecorder.RETENTION_MS, dao.prunedBefore)
        }

    @Test
    fun `a quiet poll and a crashed poll never look the same`() =
        runBlocking {
            val dao = FakeSyncRunDao()
            val recorder = RoomSyncRecorder(dao, now = { 1_000_000L })
            recorder.record(SyncRun("search:y", "search", 999_000L, 999_100L, true, 0, null))
            val run = dao.latestPerSource().single()
            assertTrue(run.ok && run.itemsAdded == 0 && run.error == null)
        }
}
