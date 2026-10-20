package com.personalos.workflow

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingJournal : Journal {
    val events = mutableListOf<JournalEvent>()

    override suspend fun record(event: JournalEvent) {
        events += event
    }

    fun starts(node: String) = events.filter { it.node == node && it.phase == JournalPhase.START }
}

/** A clock that moves once per read, so durations are non-zero and ordered. */
private class TickingClock {
    private var t = 1_000L

    fun now(): Long = t++
}

private fun engine(
    step: WorkflowStep,
    journal: Journal,
    store: RunStore = InMemoryRunStore(),
    clock: () -> Long = TickingClock()::now,
): Engine =
    Engine(
        steps = mapOf("work" to step),
        store = store,
        journal = journal,
        now = clock,
    )

private class OutcomeStep(
    private val outcome: StepOutcome,
) : WorkflowStep {
    override suspend fun run(ctx: StepContext): StepOutcome = outcome
}

class JournalTest {
    @Test
    fun `a linear run journals a START and an END per node`() {
        val w =
            workflow("linear") {
                val a = task("a", "work")
                val b = task("b", "work")
                edge(a, b)
            }
        val journal = RecordingJournal()

        runBlocking { engine(OutcomeStep(StepOutcome.Done()), journal).run(w, "r") }

        val a = journal.events.filter { it.node == "a" }
        assertEquals(listOf(JournalPhase.START, JournalPhase.END), a.map { it.phase })
        assertEquals(NodeStatus.SUCCEEDED, a.last().status)
        assertTrue((a.last().durationMs ?: -1) >= 0)
        assertEquals("workflow", a.first().host)
        assertEquals("r", a.first().runId)
        assertEquals(1, a.first().attempt)
        // and b follows
        assertTrue(journal.events.any { it.node == "b" && it.phase == JournalPhase.END })
    }

    @Test
    fun `a failure carries the step's own words`() {
        val w = workflow("one") { task("a", "work") }
        val journal = RecordingJournal()

        runBlocking {
            engine(OutcomeStep(StepOutcome.Failed(Failure.PERMANENT, "model rejected: checksum")), journal).run(w, "r")
        }

        val end = journal.events.single { it.node == "a" && it.phase == JournalPhase.END }
        assertEquals(NodeStatus.FAILED, end.status)
        assertEquals("model rejected: checksum", end.error)
    }

    @Test
    fun `each retry is its own START and END`() {
        val w =
            workflow("retry") {
                task("a", "work", attempts = 2)
            }
        val journal = RecordingJournal()
        var calls = 0
        val flaky =
            object : WorkflowStep {
                override suspend fun run(ctx: StepContext): StepOutcome {
                    calls++
                    return if (calls == 1) StepOutcome.Failed(Failure.TRANSIENT, "try again") else StepOutcome.Done("ok")
                }
            }

        runBlocking { engine(flaky, journal).run(w, "r") }

        val attempts = journal.events.filter { it.node == "a" }.groupBy { it.attempt }
        assertEquals(setOf(1, 2), attempts.keys)
        assertEquals(NodeStatus.FAILED, attempts.getValue(1).single { it.phase == JournalPhase.END }.status)
        assertEquals("try again", attempts.getValue(1).single { it.phase == JournalPhase.END }.error)
        assertEquals(NodeStatus.SUCCEEDED, attempts.getValue(2).single { it.phase == JournalPhase.END }.status)
    }

    @Test
    fun `a node that never ran is one END, never a fake START`() {
        val w =
            workflow("skip") {
                val a = task("a", "work")
                val b = task("b", "work")
                edge(a, b)
            }
        val journal = RecordingJournal()

        runBlocking { engine(OutcomeStep(StepOutcome.Failed(Failure.PERMANENT, "nope")), journal).run(w, "r") }

        val b = journal.events.filter { it.node == "b" }
        assertEquals(1, b.size)
        assertEquals(JournalPhase.END, b.single().phase)
        assertEquals(NodeStatus.SKIPPED, b.single().status)
        assertNull(b.single().durationMs)
    }

    @Test
    fun `control nodes are journalled too`() {
        val w =
            workflow("fork") {
                val root = task("root", "work")
                val f = fork("f")
                val a = task("a", "work")
                val b = task("b", "work")
                val j = join("j")
                edge(root, f)
                edge(f, a)
                edge(f, b)
                edge(a, j)
                edge(b, j)
            }
        val journal = RecordingJournal()

        runBlocking { engine(OutcomeStep(StepOutcome.Done()), journal).run(w, "r") }

        listOf("f", "j").forEach { control ->
            val events = journal.events.filter { it.node == control }
            assertEquals(listOf(JournalPhase.START, JournalPhase.END), events.map { it.phase })
            assertEquals(NodeStatus.SUCCEEDED, events.last().status)
        }
    }

    @Test
    fun `the no-op journal is a valid host`() {
        val w = workflow("noop") { task("a", "work") }
        val result = runBlocking { Engine(steps = mapOf("work" to OutcomeStep(StepOutcome.Done()))).run(w, "r") }
        assertEquals(RunStatus.SUCCEEDED, result.status)
    }
}
