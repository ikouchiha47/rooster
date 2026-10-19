package com.personalos.workflow

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The run ledger (ADR 0010): write-ahead intent, OCC, lease, and derived status —
 * the contract the durable store must also satisfy.
 */
class RunLedgerTest {
    private val node = NodeId("a")

    /** Records the nodes it is asked to run. */
    private class RecordingStep(
        private val calls: MutableList<String>,
    ) : WorkflowStep {
        override suspend fun run(ctx: StepContext): StepOutcome {
            calls += ctx.node.value
            return StepOutcome.Done()
        }
    }

    private fun oneNode() = workflow("w") { task("a", "work") }

    private fun attempt(
        status: NodeStatus = NodeStatus.RUNNING,
        attempt: Int = 1,
    ) = Attempt(runId = "r", node = node, attempt = attempt, status = status)

    // ------------------------------------------------------------ ownership

    @Test
    fun aSecondHostCannotTakeALeasedRun() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            assertTrue(ledger.claim("r", "w", 1, "a", now = 1_000, leaseMillis = 10_000))
            assertFalse(ledger.claim("r", "w", 1, "b", now = 1_001, leaseMillis = 10_000))
        }
    }

    @Test
    fun anExpiredLeaseCanBeTakenOver() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            assertTrue(ledger.claim("r", "w", 1, "a", now = 1_000, leaseMillis = 10_000))
            // 1_000 + 10_000 has passed: the previous host is presumed dead.
            assertTrue(ledger.claim("r", "w", 1, "b", now = 11_001, leaseMillis = 10_000))
            assertEquals("b", ledger.run("r")?.owner)
        }
    }

    @Test
    fun releaseFreesTheRun() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.claim("r", "w", 1, "a", now = 1_000, leaseMillis = 10_000)
            assertTrue(ledger.release("r", "a", now = 1_100))
            assertNull(ledger.run("r")?.owner)
            assertTrue(ledger.claim("r", "w", 1, "b", now = 1_101, leaseMillis = 10_000))
        }
    }

    @Test
    fun aForeignReleaseIsRefused() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.claim("r", "w", 1, "a", now = 1_000, leaseMillis = 10_000)
            assertFalse(ledger.release("r", "b", now = 1_100))
        }
    }

    // ------------------------------------------------------------ attempts / OCC

    @Test
    fun aDuplicateAttemptNumberIsALoudBug() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.beginAttempt(attempt())
            // The number came from nextAttempt, so a duplicate means a real defect.
            assertFailsWith<IllegalStateException> { ledger.beginAttempt(attempt()) }
            ledger.beginAttempt(attempt(attempt = 2))
        }
    }

    @Test
    fun aStaleOccWriteIsRefused() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.beginAttempt(attempt())
            assertTrue(ledger.finishAttempt("r", node, 1, 1, NodeStatus.SUCCEEDED, null, now = 2))
            // The row is now at version 2; a write quoting version 1 is stale.
            assertFalse(ledger.finishAttempt("r", node, 1, 1, NodeStatus.FAILED, null, now = 3))
            assertEquals(NodeStatus.SUCCEEDED, ledger.attempts("r").single().status)
        }
    }

    // ------------------------------------------------------------ derived status

    @Test
    fun statusIsRunningWhileAnAttemptIsUnsettled() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.beginAttempt(attempt())
            assertEquals(RunStatus.RUNNING, ledger.status("r"))
        }
    }

    @Test
    fun statusIsFailedIfAnyAttemptFailed() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.beginAttempt(attempt(attempt = 1))
            ledger.finishAttempt("r", node, 1, 1, NodeStatus.FAILED, null, now = 2)
            assertEquals(RunStatus.FAILED, ledger.status("r"))
        }
    }

    @Test
    fun statusIsSucceededWhenEveryAttemptIsTerminal() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.beginAttempt(attempt(attempt = 1))
            ledger.finishAttempt("r", node, 1, 1, NodeStatus.SUCCEEDED, null, now = 2)
            assertEquals(RunStatus.SUCCEEDED, ledger.status("r"))
        }
    }

    @Test
    fun aSkippedNodeStillCountsAsSuccess() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.beginAttempt(attempt())
            ledger.finishAttempt("r", node, 1, 1, NodeStatus.SKIPPED, null, now = 2)
            assertEquals(RunStatus.SUCCEEDED, ledger.status("r"))
        }
    }

    // ------------------------------------------------------------ engine integration

    @Test
    fun intentIsWrittenBeforeTheStepRuns() {
        val ledger = InMemoryRunLedger()
        var seen: NodeStatus? = null
        val step =
            object : WorkflowStep {
                override suspend fun run(ctx: StepContext): StepOutcome {
                    // Inside the step, our own attempt must already exist and be RUNNING.
                    seen = ledger.attempts(ctx.runId).firstOrNull { it.node == ctx.node }?.status
                    return StepOutcome.Done()
                }
            }
        val engine = Engine(steps = mapOf("work" to step), ledger = ledger, owner = "t", now = { 1L })

        runBlocking { engine.run(oneNode(), "r") }

        assertEquals(NodeStatus.RUNNING, seen)
    }

    @Test
    fun theEngineClaimsAndReleasesTheRun() {
        val ledger = InMemoryRunLedger()
        val engine =
            Engine(
                steps = mapOf("work" to RecordingStep(mutableListOf())),
                ledger = ledger,
                owner = "t",
                now = { 1L },
            )

        val (result, run) =
            runBlocking {
                val outcome = engine.run(oneNode(), "r")
                outcome to ledger.run("r")
            }

        assertEquals(RunStatus.SUCCEEDED, result.status)
        assertNull(assertNotNull(run).owner, "the lease is released at the end")
        assertEquals(1, assertNotNull(run).definitionVersion)
    }

    @Test
    fun provenanceAndOutcomeAreRecorded() {
        val ledger = InMemoryRunLedger()
        val step =
            object : WorkflowStep {
                override suspend fun run(ctx: StepContext): StepOutcome = StepOutcome.Done("7")
            }
        val engine =
            Engine(
                steps = mapOf("work" to step),
                ledger = ledger,
                owner = "t",
                invokedBy = "workflow:r",
                now = { 1L },
            )

        val row =
            runBlocking {
                engine.run(oneNode(), "r")
                ledger.attempts("r").single()
            }

        assertEquals("workflow:r", row.invokedBy)
        assertEquals("r", row.correlationId)
        assertEquals(NodeStatus.SUCCEEDED, row.status)
        assertEquals("7", row.outputs)
    }

    @Test
    fun theEngineDoesNotWalkARunAnotherHostOwns() {
        val ledger = InMemoryRunLedger()
        val calls = mutableListOf<String>()
        runBlocking { ledger.claim("r", "w", 1, "other", now = 1_000, leaseMillis = 10_000) }
        val engine =
            Engine(
                steps = mapOf("work" to RecordingStep(calls)),
                ledger = ledger,
                owner = "me",
                now = { 1_500L },
            )

        val result = runBlocking { engine.run(oneNode(), "r") }

        assertEquals(emptyList(), calls)
        assertEquals(RunStatus.RUNNING, result.status)
    }

    @Test
    fun nextAttemptContinuesWhereItLeftOff() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            assertEquals(1, ledger.nextAttempt("r", node))
            ledger.beginAttempt(attempt())
            assertEquals(2, ledger.nextAttempt("r", node))
            ledger.beginAttempt(attempt(attempt = 2))
            assertEquals(3, ledger.nextAttempt("r", node))
        }
    }

    @Test
    fun aSupersededInFlightAttemptDoesNotPinTheRun() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            // Attempt 1 was running when the host died; attempt 2 finished.
            ledger.beginAttempt(attempt(attempt = 1))
            ledger.beginAttempt(attempt(attempt = 2))
            ledger.finishAttempt("r", node, 2, 1, NodeStatus.SUCCEEDED, null, now = 2)

            assertEquals(RunStatus.SUCCEEDED, ledger.status("r"))
        }
    }

    @Test
    fun aSupersededFailureDoesNotFailTheRun() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.beginAttempt(attempt(attempt = 1))
            ledger.finishAttempt("r", node, 1, 1, NodeStatus.FAILED, null, now = 2)
            ledger.beginAttempt(attempt(attempt = 2))
            ledger.finishAttempt("r", node, 2, 1, NodeStatus.SUCCEEDED, null, now = 3)

            assertEquals(RunStatus.SUCCEEDED, ledger.status("r"))
        }
    }

    @Test
    fun aResumedRunContinuesAtTheNextAttempt() {
        val ledger = InMemoryRunLedger()
        runBlocking {
            ledger.claim("r", "w", 1, "t", now = 0, leaseMillis = 1_000)
            ledger.release("r", "t", now = 1)
            // Attempt 1 was in flight when the process died, so it is not terminal.
            ledger.beginAttempt(Attempt("r", node, 1, NodeStatus.RUNNING))
        }
        val engine =
            Engine(
                steps = mapOf("work" to RecordingStep(mutableListOf())),
                ledger = ledger,
                owner = "t",
                now = { 2L },
            )

        val attempts =
            runBlocking {
                engine.run(oneNode(), "r")
                ledger.attempts("r")
            }

        assertEquals(listOf(1, 2), attempts.map { it.attempt })
        assertEquals(NodeStatus.SUCCEEDED, attempts.maxByOrNull { it.attempt }?.status)
    }

    // ------------------------------------------------------------ Model 1 (stage)

    @Test
    fun aStageRunWritesNoLedgerRows() {
        // Model 1: no ledger is passed, so nothing is recorded — the stage's only
        // state is its own cursor. This is the n=1 case of the same engine.
        val ledger = InMemoryRunLedger()
        val engine = Engine(steps = mapOf("work" to RecordingStep(mutableListOf())))

        runBlocking {
            engine.run(oneNode(), "r")
            assertEquals(emptyList(), ledger.attempts("r"))
            assertNull(ledger.run("r"))
        }
    }
}
