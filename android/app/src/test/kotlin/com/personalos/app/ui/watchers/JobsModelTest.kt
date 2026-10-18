package com.personalos.app.ui.watchers

import com.personalos.app.core.Chars
import com.personalos.app.data.work.JobSnapshot
import com.personalos.app.data.work.JobState
import com.personalos.app.data.work.WorkerJobSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background-job decisions, tested without rendering or WorkManager: row
 * order, header counts, honest state words, progress math and its clamping,
 * and the fallbacks when a worker reports no meaningful progress.
 *
 * The composable only lays these out; [JobSnapshot] (built in the data
 * layer from WorkInfo) is the only input.
 */
class JobsModelTest {
    private fun spec(
        name: String,
        label: String = name,
        group: String = name,
        countKey: String? = "added",
        singular: String? = "item",
        plural: String? = "items",
    ) = WorkerJobSpec(
        uniqueName = name,
        label = label,
        group = group,
        countKey = countKey,
        singular = singular,
        plural = plural,
    )

    private fun snap(
        name: String,
        state: JobState,
        retrying: Boolean = false,
        done: Int? = null,
        total: Int? = null,
        count: Int? = null,
        errorKind: String? = null,
        label: String = name,
        group: String = name,
        countKey: String? = "added",
        singular: String? = "item",
        plural: String? = "items",
    ) = JobSnapshot(
        spec = spec(name, label, group, countKey, singular, plural),
        state = state,
        retrying = retrying,
        progressDone = done,
        progressTotal = total,
        count = count,
        errorKind = errorKind,
    )

    // ------------------------------------------------------------------ header

    @Test
    fun `the header counts jobs and running jobs`() {
        assertEquals("0 JOBS ${Chars.MIDDLE_DOT} 0 RUNNING", workersHeader(emptyList()))
        val rows =
            workerRows(
                listOf(
                    snap("feed-sync", JobState.RUNNING),
                    snap("sms-sync", JobState.SUCCEEDED, count = 3),
                ),
            )
        assertEquals("2 JOBS ${Chars.MIDDLE_DOT} 1 RUNNING", workersHeader(rows))
    }

    @Test
    fun `the section note names running work, then failures, then stays quiet`() {
        val running = workerRows(listOf(snap("a", JobState.RUNNING), snap("b", JobState.IDLE)))
        assertEquals("1 running", workersNote(running))
        val failed = workerRows(listOf(snap("a", JobState.FAILED), snap("b", JobState.SUCCEEDED)))
        assertEquals("1 failed", workersNote(failed))
        val quiet = workerRows(listOf(snap("a", JobState.SUCCEEDED), snap("b", JobState.IDLE)))
        assertNull(workersNote(quiet))
    }

    // ------------------------------------------------------------------- order

    @Test
    fun `running jobs read first, quiet jobs last`() {
        val rows =
            workerRows(
                listOf(
                    snap("idle", JobState.IDLE),
                    snap("ok", JobState.SUCCEEDED),
                    snap("failed", JobState.FAILED),
                    snap("queued", JobState.SCHEDULED),
                    snap("busy", JobState.RUNNING),
                ),
            )
        assertEquals(
            listOf("busy", "queued", "failed", "ok", "idle"),
            workersOrder(rows).map { it.key },
        )
    }

    @Test
    fun `jobs at the same state fall back to label`() {
        val rows =
            workerRows(
                listOf(
                    snap("b", JobState.SUCCEEDED, label = "Beta"),
                    snap("a", JobState.SUCCEEDED, label = "Alpha"),
                ),
            )
        assertEquals(listOf("a", "b"), workersOrder(rows).map { it.key })
    }

    @Test
    fun `ordering does not invent or drop jobs`() {
        val rows = workerRows(listOf(snap("a", JobState.IDLE), snap("b", JobState.IDLE)))
        val ordered = workersOrder(rows)
        assertEquals(2, ordered.size)
        assertFalse(ordered.map { it.key }.toSet() != setOf("a", "b"))
    }

    // ------------------------------------------------------------------ states

    @Test
    fun `states are honest words the data can support`() {
        assertEquals("running", jobStateText(JobState.RUNNING, false))
        assertEquals("scheduled", jobStateText(JobState.SCHEDULED, false))
        assertEquals("retrying", jobStateText(JobState.SCHEDULED, true))
        assertEquals("waiting", jobStateText(JobState.WAITING, false))
        assertEquals("last run ok", jobStateText(JobState.SUCCEEDED, false))
        assertEquals("last run failed", jobStateText(JobState.FAILED, false))
        assertEquals("stopped", jobStateText(JobState.STOPPED, false))
        assertEquals("idle", jobStateText(JobState.IDLE, false))
    }

    @Test
    fun `a scheduled job still reports its last run take`() {
        val row = workerRowOf(snap("sms-sync", JobState.SCHEDULED, count = 3))
        assertEquals("scheduled", row.stateText)
        assertEquals("+3 items", row.detailText)
        assertNull(row.fraction)
    }

    @Test
    fun `a failure reports the error kind, not a placeholder`() {
        val row = workerRowOf(snap("feed-sync", JobState.FAILED, errorKind = "UnknownHostException"))
        assertEquals("last run failed", row.stateText)
        assertEquals("UnknownHostException", row.detailText)
        assertNull(row.fraction)
    }

    // ---------------------------------------------------------------- progress

    @Test
    fun `progress fraction is done over total`() {
        assertEquals(0.15f, progressFraction(3, 20))
        assertEquals(1f, progressFraction(20, 20))
        assertEquals(0f, progressFraction(0, 20))
    }

    @Test
    fun `progress fraction clamps rather than lying`() {
        assertEquals(1f, progressFraction(25, 20))
        assertEquals(0f, progressFraction(0, 20))
    }

    @Test
    fun `progress without a meaningful denominator is no bar`() {
        assertNull(progressFraction(3, 0))
        assertNull(progressFraction(3, -1))
        assertNull(progressFraction(-1, 20))
        assertNull(progressFraction(null, 20))
        assertNull(progressFraction(3, null))
        assertNull(progressFraction(null, null))
    }

    @Test
    fun `a running job with progress shows the bar and the counts`() {
        val row = workerRowOf(snap("article-enrich", JobState.RUNNING, done = 3, total = 20))
        assertEquals("running", row.stateText)
        assertEquals("3 of 20 items", row.detailText)
        assertEquals(0.15f, row.fraction)
    }

    @Test
    fun `a running job without observable progress shows state with no bar`() {
        val row = workerRowOf(snap("feed-sync", JobState.RUNNING))
        assertEquals("running", row.stateText)
        assertNull(row.detailText)
        assertNull(row.fraction)
    }

    @Test
    fun `a finished job never shows a bar`() {
        val row = workerRowOf(snap("sms-sync", JobState.SUCCEEDED, count = 3, done = 3, total = 3))
        assertNull(row.fraction)
        assertEquals("+3 items", row.detailText)
    }

    // ----------------------------------------------------------------- outcome

    @Test
    fun `outcome counts use the singular for one`() {
        assertEquals("+1 item", outcomeText(1, "item", "items"))
        assertEquals("+3 items", outcomeText(3, "item", "items"))
    }

    @Test
    fun `a zero or missing count is not an outcome`() {
        assertNull(outcomeText(0, "item", "items"))
        assertNull(outcomeText(-1, "item", "items"))
        assertNull(outcomeText(null, "item", "items"))
        assertNull(outcomeText(3, null, null))
    }

    // --------------------------------------------------------------- grouping

    @Test
    fun `rule backfills collapse into one row`() {
        val rows =
            workerRows(
                listOf(
                    snap("rule-backfill:a", JobState.SUCCEEDED, count = 2, group = "rule-backfill", countKey = "matches", singular = "match", plural = "matches"),
                    snap("rule-backfill:b", JobState.SUCCEEDED, count = 5, group = "rule-backfill", countKey = "matches", singular = "match", plural = "matches"),
                    snap("feed-sync", JobState.IDLE),
                ),
            )
        assertEquals(2, rows.size)
        val backfill = rows.single { it.key == "group:rule-backfill" }
        assertEquals("last run ok", backfill.stateText)
        assertEquals("+7 matches", backfill.detailText)
    }

    @Test
    fun `an aggregate row surfaces running before queued before failed`() {
        val busy =
            workerRows(
                listOf(
                    snap("rule-backfill:a", JobState.SUCCEEDED, group = "rule-backfill"),
                    snap("rule-backfill:b", JobState.RUNNING, group = "rule-backfill"),
                ),
            ).single { it.key == "group:rule-backfill" }
        assertEquals("running", busy.stateText)

        val queued =
            workerRows(
                listOf(
                    snap("rule-backfill:a", JobState.SUCCEEDED, group = "rule-backfill"),
                    snap("rule-backfill:b", JobState.SCHEDULED, group = "rule-backfill"),
                    snap("rule-backfill:c", JobState.SCHEDULED, group = "rule-backfill"),
                ),
            ).single { it.key == "group:rule-backfill" }
        assertEquals("scheduled", queued.stateText)
        assertEquals("2 queued", queued.detailText)
    }

    @Test
    fun `a new registered worker renders from its registration, not a branch`() {
        val row = workerRowOf(snap("future-sync", JobState.SUCCEEDED, count = 4, label = "Future sync"))
        assertEquals("Future sync", row.label)
        assertEquals("last run ok", row.stateText)
        assertEquals("+4 items", row.detailText)
    }

    @Test
    fun `a worker with no count key reports state only`() {
        val bare =
            JobSnapshot(
                spec = WorkerJobSpec(uniqueName = "retag-dispatch", label = "Retag"),
                state = JobState.SUCCEEDED,
                retrying = false,
                progressDone = null,
                progressTotal = null,
                count = null,
                errorKind = null,
            )
        val row = workerRowOf(bare)
        assertEquals("last run ok", row.stateText)
        assertNull(row.detailText)
        assertNull(row.fraction)
    }

    @Test
    fun `idle workers read last and say so`() {
        val row = workerRowOf(snap("launch-backfill", JobState.IDLE))
        assertEquals("idle", row.stateText)
        assertNull(row.detailText)
        assertTrue(workersOrder(workerRows(listOf(snap("b", JobState.IDLE), snap("a", JobState.RUNNING)))).last().key == "b")
    }
}
