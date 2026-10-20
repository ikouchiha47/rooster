package com.personalos.app.data.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enqueue policy without WorkManager: the recon's `enqueueIfIdle` must never
 * duplicate a fast job that is already queued, running or blocked, and the
 * chain must append rather than replace its own runner.
 */
class DrainSchedulerTest {
    private class FakeQueue(
        var states: List<DrainWorkState> = emptyList(),
    ) : DrainQueue {
        data class Enqueued(
            val name: String,
            val policy: ExistingWorkPolicy,
            val delaySeconds: Long,
        )

        val enqueued = mutableListOf<Enqueued>()
        var periodic: Triple<String, ExistingPeriodicWorkPolicy, Long>? = null

        override fun states(uniqueName: String): List<DrainWorkState> = states

        override fun enqueue(
            uniqueName: String,
            policy: ExistingWorkPolicy,
            spec: DrainSpec,
            delaySeconds: Long,
        ) {
            enqueued += Enqueued(uniqueName, policy, delaySeconds)
        }

        override fun enqueuePeriodic(
            uniqueName: String,
            policy: ExistingPeriodicWorkPolicy,
            spec: DrainSpec,
            minutes: Long,
        ) {
            periodic = Triple(uniqueName, policy, minutes)
        }
    }

    private fun scheduler(queue: FakeQueue) = DrainScheduler(Drains.RETAG, queue)

    @Test
    fun `enqueueIfIdle does not enqueue while the fast job is enqueued`() {
        val queue = FakeQueue(listOf(DrainWorkState(WorkInfo.State.ENQUEUED, 0)))
        scheduler(queue).enqueueIfIdle()

        assertTrue(queue.enqueued.isEmpty())
    }

    @Test
    fun `enqueueIfIdle does not enqueue while the fast job is running`() {
        val queue = FakeQueue(listOf(DrainWorkState(WorkInfo.State.RUNNING, 0)))
        scheduler(queue).enqueueIfIdle()

        assertTrue(queue.enqueued.isEmpty())
    }

    @Test
    fun `enqueueIfIdle does not enqueue while the fast job is blocked`() {
        val queue = FakeQueue(listOf(DrainWorkState(WorkInfo.State.BLOCKED, 0)))
        scheduler(queue).enqueueIfIdle()

        assertTrue(queue.enqueued.isEmpty())
    }

    @Test
    fun `enqueueIfIdle starts the fast drain when it is idle`() {
        val queue = FakeQueue(listOf(DrainWorkState(WorkInfo.State.SUCCEEDED, 0)))
        scheduler(queue).enqueueIfIdle()

        assertEquals(1, queue.enqueued.size)
        assertEquals(Drains.RETAG.uniqueName, queue.enqueued.single().name)
    }

    @Test
    fun `enqueueIfIdle replaces a stale enqueued job that exhausted its attempts`() {
        val queue = FakeQueue(listOf(DrainWorkState(WorkInfo.State.ENQUEUED, 9)))
        scheduler(queue).enqueueIfIdle()

        assertEquals(1, queue.enqueued.size)
        assertEquals(ExistingWorkPolicy.REPLACE, queue.enqueued.single().policy)
    }

    @Test
    fun `enqueueNow leaves a running batch alone and replaces anything else`() {
        val running = FakeQueue(listOf(DrainWorkState(WorkInfo.State.RUNNING, 0)))
        scheduler(running).enqueueNow()
        assertEquals(ExistingWorkPolicy.KEEP, running.enqueued.single().policy)

        val idle = FakeQueue(listOf(DrainWorkState(WorkInfo.State.FAILED, 0)))
        scheduler(idle).enqueueNow()
        assertEquals(ExistingWorkPolicy.REPLACE, idle.enqueued.single().policy)
    }

    @Test
    fun `a follow-up appends after the current run with the spec's pacing`() {
        val queue = FakeQueue()
        scheduler(queue).enqueueFollowUp()

        val request = queue.enqueued.single()
        assertEquals(Drains.RETAG.uniqueName, request.name)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, request.policy)
        assertEquals(Drains.RETAG.chainDelaySeconds, request.delaySeconds)
    }

    @Test
    fun `the recon is registered under its own periodic name with its interval`() {
        val queue = FakeQueue()
        scheduler(queue).ensurePeriodic()

        val periodic = queue.periodic!!
        assertEquals(Drains.RETAG.reconUniqueName, periodic.first)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, periodic.second)
        assertEquals(30L, periodic.third)
    }
}
