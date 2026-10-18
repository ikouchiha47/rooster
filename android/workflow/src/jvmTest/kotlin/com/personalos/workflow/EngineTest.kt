package com.personalos.workflow

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Records every node it is asked to run, and answers with a per-node outcome. */
private class FakeStep(
    private val outcomes: Map<String, StepOutcome> = emptyMap(),
) : WorkflowStep {
    val calls = mutableListOf<String>()

    override suspend fun run(ctx: StepContext): StepOutcome {
        calls += ctx.node.value
        return outcomes[ctx.node.value] ?: StepOutcome.Done
    }
}

/** Fails transiently [failTimes] times, then succeeds. */
private class FlakyStep(
    private val failTimes: Int,
) : WorkflowStep {
    var count = 0

    override suspend fun run(ctx: StepContext): StepOutcome {
        count++
        return if (count <= failTimes) StepOutcome.Failed(Failure.TRANSIENT) else StepOutcome.Done
    }
}

private class FakePredicate(
    val answer: Boolean,
) : WorkflowPredicate {
    override suspend fun test(ctx: StepContext): Boolean = answer
}

private fun engine(
    step: WorkflowStep,
    predicate: WorkflowPredicate? = null,
    store: RunStore = InMemoryRunStore(),
): Engine =
    Engine(
        steps = mapOf("work" to step),
        predicates = if (predicate == null) emptyMap() else mapOf("when" to predicate),
        store = store,
    )

class EngineTest {
    @Test
    fun linearChainRunsInOrder() {
        val w =
            workflow("linear") {
                val a = task("a", "work")
                val b = task("b", "work")
                val c = task("c", "work")
                edge(a, b)
                edge(b, c)
            }
        val step = FakeStep()

        val result = runBlocking { engine(step).run(w, "r") }

        assertEquals(listOf("a", "b", "c"), step.calls)
        assertEquals(RunStatus.SUCCEEDED, result.status)
    }

    @Test
    fun forkRunsBranchesAndJoinWaitsForBoth() {
        val w =
            workflow("forkjoin") {
                val f = fork("f")
                val b1 = task("b1", "work")
                val b2 = task("b2", "work")
                val j = join("j")
                val end = task("end", "work")
                edge(f, b1)
                edge(f, b2)
                edge(b1, j)
                edge(b2, j)
                edge(j, end)
            }
        val step = FakeStep()

        val result = runBlocking { engine(step).run(w, "r") }

        assertEquals(setOf("b1", "b2", "end"), step.calls.toSet())
        assertEquals("end", step.calls.last())
        assertEquals(RunStatus.SUCCEEDED, result.status)
    }

    @Test
    fun branchTakesThenAndSkipsElse() {
        val w =
            workflow("branch") {
                val p = branch("p", "when")
                val t = task("t", "work")
                val e = task("e", "work")
                val j = join("j")
                val end = task("end", "work")
                edge(p, t, Branch.THEN)
                edge(p, e, Branch.ELSE)
                edge(t, j)
                edge(e, j)
                edge(j, end)
            }
        val step = FakeStep()

        val result = runBlocking { engine(step, FakePredicate(true)).run(w, "r") }

        assertTrue(step.calls.contains("t"))
        assertTrue(!step.calls.contains("e"))
        assertEquals(NodeStatus.SKIPPED, result.nodes[NodeId("e")])
        assertEquals(NodeStatus.SUCCEEDED, result.nodes[NodeId("j")])
        assertEquals(NodeStatus.SUCCEEDED, result.nodes[NodeId("end")])
        assertEquals(RunStatus.SUCCEEDED, result.status)
    }

    @Test
    fun branchTakesElseAndSkipsThen() {
        val w =
            workflow("branch") {
                val p = branch("p", "when")
                val t = task("t", "work")
                val e = task("e", "work")
                edge(p, t, Branch.THEN)
                edge(p, e, Branch.ELSE)
            }
        val step = FakeStep()

        val result = runBlocking { engine(step, FakePredicate(false)).run(w, "r") }

        assertEquals(NodeStatus.SKIPPED, result.nodes[NodeId("t")])
        assertEquals(NodeStatus.SUCCEEDED, result.nodes[NodeId("e")])
    }

    @Test
    fun completedNodeIsNotRerun() {
        val w =
            workflow("resume") {
                val a = task("a", "work")
                val b = task("b", "work")
                edge(a, b)
            }
        val store = InMemoryRunStore()
        runBlocking { store.put("r", NodeId("a"), NodeState(NodeStatus.SUCCEEDED)) }
        val step = FakeStep()

        val result = runBlocking { engine(step, store = store).run(w, "r") }

        assertEquals(listOf("b"), step.calls)
        assertEquals(RunStatus.SUCCEEDED, result.status)
    }

    @Test
    fun permanentFailureFailsRunAndSkipsDownstream() {
        val w =
            workflow("failure") {
                val a = task("a", "work")
                val b = task("b", "work")
                val c = task("c", "work")
                edge(a, b)
                edge(b, c)
            }
        val step = FakeStep(mapOf("b" to StepOutcome.Failed(Failure.PERMANENT)))

        val result = runBlocking { engine(step).run(w, "r") }

        assertEquals(NodeStatus.FAILED, result.nodes[NodeId("b")])
        assertEquals(NodeStatus.SKIPPED, result.nodes[NodeId("c")])
        assertEquals(RunStatus.FAILED, result.status)
    }

    @Test
    fun pausedRunIsResumable() {
        val w =
            workflow("pause") {
                val a = task("a", "work")
                val b = task("b", "work")
                edge(a, b)
            }
        val step = FakeStep(mapOf("b" to StepOutcome.Paused))

        val result = runBlocking { engine(step).run(w, "r") }

        assertEquals(NodeStatus.PAUSED, result.nodes[NodeId("b")])
        assertEquals(RunStatus.PAUSED, result.status)
    }

    @Test
    fun transientFailureIsRetriedUpToAttempts() {
        val w =
            workflow("retry") {
                task("a", "work", attempts = 3)
            }
        val step = FlakyStep(failTimes = 2)

        val result = runBlocking { engine(step).run(w, "r") }

        assertEquals(3, step.count)
        assertEquals(RunStatus.SUCCEEDED, result.status)
    }

    @Test
    fun transientFailureIsExhausted() {
        val w =
            workflow("retry") {
                task("a", "work", attempts = 2)
            }
        val step = FlakyStep(failTimes = 5)

        val result = runBlocking { engine(step).run(w, "r") }

        assertEquals(NodeStatus.RETRY_EXHAUSTED, result.nodes[NodeId("a")])
        assertEquals(RunStatus.FAILED, result.status)
    }
}
