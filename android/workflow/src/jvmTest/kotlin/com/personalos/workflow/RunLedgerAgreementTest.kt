package com.personalos.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.personalos.workflow.db.SqlDelightRunLedger
import com.personalos.workflow.db.WorkflowDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The durable ledger must be indistinguishable from the in-memory one on the
 * contract that matters: claims, take-overs, OCC, provenance and derived status.
 */
class RunLedgerAgreementTest {
    private val node = NodeId("a")

    private fun durable(): RunLedger {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorkflowDatabase.Schema.create(driver)
        return SqlDelightRunLedger(WorkflowDatabase(driver))
    }

    /** One scripted sequence; the observable results must match across stores. */
    private fun script(ledger: RunLedger): List<Any?> =
        runBlocking {
            val created = ledger.claim("r", "w", 3, "a", now = 1_000, leaseMillis = 10_000)
            val heldByAnother = ledger.claim("r", "w", 3, "b", now = 1_001, leaseMillis = 10_000)
            val takenOver = ledger.claim("r", "w", 3, "b", now = 20_000, leaseMillis = 10_000)
            ledger.beginAttempt(
                Attempt("r", node, 1, NodeStatus.RUNNING, startedAt = 20_000, invokedBy = "workflow:r", correlationId = "r"),
            )
            val settled = ledger.finishAttempt("r", node, 1, 1, NodeStatus.SUCCEEDED, "7", now = 20_001)
            val stale = ledger.finishAttempt("r", node, 1, 1, NodeStatus.FAILED, null, now = 20_002)
            val released = ledger.release("r", "b", now = 20_003)
            val run = ledger.run("r")
            listOf(
                created,
                heldByAnother,
                takenOver,
                settled,
                stale,
                released,
                ledger.status("r"),
                run?.workflowId,
                run?.definitionVersion,
                run?.owner,
                ledger.attempts("r").map { it.status to it.outputs },
            )
        }

    @Test
    fun theDurableLedgerAgreesWithTheInMemoryOne() {
        assertEquals(script(InMemoryRunLedger()), script(durable()))
    }

    @Test
    fun theScriptIsWhatWeThinkItIs() {
        // Guard against the agreement test passing because both stores are wrong.
        assertEquals(
            listOf(
                true, // created
                false, // held by another
                true, // taken over after expiry
                true, // settled
                false, // stale OCC refused
                true, // released
                RunStatus.SUCCEEDED,
                "w",
                3,
                null,
                listOf(NodeStatus.SUCCEEDED to "7"),
            ),
            script(InMemoryRunLedger()),
        )
    }
}
