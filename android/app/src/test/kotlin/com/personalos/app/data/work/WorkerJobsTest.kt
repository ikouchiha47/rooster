package com.personalos.app.data.work

import androidx.work.Data
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The WorkInfo summary, tested with real WorkInfo records: which state wins,
 * what a scheduled periodic job keeps from its last run, and that progress
 * only survives with a real denominator.
 */
class WorkerJobsTest {
    private val spec =
        WorkerJobSpec(
            uniqueName = "feed-sync",
            label = "Feed sync",
            countKey = "added",
            singular = "item",
            plural = "items",
        )

    private fun info(
        state: WorkInfo.State,
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
        attempt: Int = 0,
    ) = WorkInfo(UUID.randomUUID(), state, emptySet(), output, progress, attempt)

    private fun output(vararg pairs: Pair<String, Any>): Data {
        val builder = Data.Builder()
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> builder.putInt(key, value)
                is String -> builder.putString(key, value)
                else -> throw IllegalArgumentException("unsupported $value")
            }
        }
        return builder.build()
    }

    @Test
    fun `no records means idle, not never-ran`() {
        val snapshot = summarizeJob(spec, emptyList())
        assertEquals(JobState.IDLE, snapshot.state)
        assertNull(snapshot.count)
    }

    @Test
    fun `running wins over everything and carries progress`() {
        val progress = output(KEY_PROGRESS_DONE to 3, KEY_PROGRESS_TOTAL to 20)
        val snapshot =
            summarizeJob(
                spec,
                listOf(
                    info(WorkInfo.State.ENQUEUED),
                    info(WorkInfo.State.RUNNING, progress = progress),
                ),
            )
        assertEquals(JobState.RUNNING, snapshot.state)
        assertEquals(3, snapshot.progressDone)
        assertEquals(20, snapshot.progressTotal)
    }

    @Test
    fun `running without a denominator carries no progress`() {
        val snapshot = summarizeJob(spec, listOf(info(WorkInfo.State.RUNNING)))
        assertEquals(JobState.RUNNING, snapshot.state)
        assertNull(snapshot.progressDone)
        assertNull(snapshot.progressTotal)
    }

    @Test
    fun `an enqueued attempt past the first is a retry`() {
        val waiting = summarizeJob(spec, listOf(info(WorkInfo.State.ENQUEUED)))
        assertEquals(JobState.SCHEDULED, waiting.state)
        assertFalse(waiting.retrying)

        val retrying = summarizeJob(spec, listOf(info(WorkInfo.State.ENQUEUED, attempt = 2)))
        assertEquals(JobState.SCHEDULED, retrying.state)
        assertTrue(retrying.retrying)
    }

    @Test
    fun `blocked reads as waiting`() {
        val snapshot = summarizeJob(spec, listOf(info(WorkInfo.State.BLOCKED)))
        assertEquals(JobState.WAITING, snapshot.state)
    }

    @Test
    fun `a scheduled periodic job keeps its last run take`() {
        val snapshot =
            summarizeJob(
                spec,
                listOf(info(WorkInfo.State.ENQUEUED, output = output("added" to 3))),
            )
        assertEquals(JobState.SCHEDULED, snapshot.state)
        assertEquals(3, snapshot.count)
    }

    @Test
    fun `a success reports its count`() {
        val snapshot =
            summarizeJob(
                spec,
                listOf(info(WorkInfo.State.SUCCEEDED, output = output("added" to 5))),
            )
        assertEquals(JobState.SUCCEEDED, snapshot.state)
        assertEquals(5, snapshot.count)
    }

    @Test
    fun `a failure reports the recorded error kind`() {
        val snapshot =
            summarizeJob(
                spec,
                listOf(info(WorkInfo.State.FAILED, output = output(KEY_ERROR to "UnknownHostException"))),
            )
        assertEquals(JobState.FAILED, snapshot.state)
        assertEquals("UnknownHostException", snapshot.errorKind)
    }

    @Test
    fun `a failure beats an older success`() {
        val snapshot =
            summarizeJob(
                spec,
                listOf(
                    info(WorkInfo.State.SUCCEEDED, output = output("added" to 5)),
                    info(WorkInfo.State.FAILED, output = output(KEY_ERROR to "SQLiteException")),
                ),
            )
        assertEquals(JobState.FAILED, snapshot.state)
    }

    @Test
    fun `cancelled reads as stopped`() {
        val snapshot = summarizeJob(spec, listOf(info(WorkInfo.State.CANCELLED)))
        assertEquals(JobState.STOPPED, snapshot.state)
    }

    @Test
    fun `the error kind is the exception kind, never a message`() {
        assertEquals("UnknownHostException", failureKindName(java.net.UnknownHostException()))
        assertEquals("IllegalStateException", failureKindName(IllegalStateException("gone")))
    }

    @Test
    fun `drain specs derive honest registrations and counts`() {
        val enrich = Drains.ENRICHMENT.workerSpec()
        assertEquals("article-enrich-now", enrich.uniqueName)
        assertEquals("enrichment", enrich.group)
        assertEquals(EnrichWorker.KEY_ENRICHED, enrich.countKey)
        assertEquals("summary", enrich.singular)

        val retag = Drains.RETAG.workerSpec()
        assertEquals("retag-dispatch", retag.uniqueName)
        assertEquals("retag", retag.group)
        assertEquals(RetagDispatchWorker.KEY_TAGGED, retag.countKey)
        assertEquals("item", retag.singular)
    }

    @Test
    fun `both drains and their recons appear in scheduled jobs`() {
        val names = SyncScheduler.scheduledJobs().map { it.uniqueName }.toSet()

        assertTrue(Drains.ENRICHMENT.uniqueName in names)
        assertTrue(Drains.ENRICHMENT.reconUniqueName in names)
        assertTrue(Drains.RETAG.uniqueName in names)
        assertTrue(Drains.RETAG.reconUniqueName in names)
    }
}
