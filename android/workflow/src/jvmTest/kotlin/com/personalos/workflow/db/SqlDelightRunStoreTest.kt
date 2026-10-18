package com.personalos.workflow.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.personalos.workflow.Branch
import com.personalos.workflow.NodeId
import com.personalos.workflow.NodeState
import com.personalos.workflow.NodeStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlDelightRunStoreTest {
    private fun inMemory(): SqlDelightRunStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WorkflowDatabase.Schema.create(driver)
        return SqlDelightRunStore(WorkflowDatabase(driver))
    }

    @Test
    fun putAndGetRoundTrip() {
        val store = inMemory()
        runBlocking {
            store.put("r", NodeId("a"), NodeState(NodeStatus.SUCCEEDED, Branch.THEN))
            assertEquals(NodeState(NodeStatus.SUCCEEDED, Branch.THEN), store.get("r", NodeId("a")))
            assertNull(store.get("r", NodeId("b")))
        }
    }

    @Test
    fun putIsIdempotentPerNode() {
        val store = inMemory()
        runBlocking {
            store.put("r", NodeId("a"), NodeState(NodeStatus.PENDING))
            store.put("r", NodeId("a"), NodeState(NodeStatus.SUCCEEDED))
            assertEquals(NodeStatus.SUCCEEDED, store.get("r", NodeId("a"))?.status)
        }
    }

    @Test
    fun allReturnsEveryNodeOfTheRun() {
        val store = inMemory()
        runBlocking {
            store.put("r", NodeId("a"), NodeState(NodeStatus.SUCCEEDED))
            store.put("r", NodeId("b"), NodeState(NodeStatus.SKIPPED))
            store.put("other", NodeId("c"), NodeState(NodeStatus.FAILED))
            assertEquals(setOf(NodeId("a"), NodeId("b")), store.all("r").keys)
        }
    }

    @Test
    fun stateSurvivesReopeningTheDatabase() {
        val file = File.createTempFile("workflow", ".db").apply { delete() }
        try {
            runBlocking {
                SqlDelightRunStore(WorkflowDatabase(jvmWorkflowDriver(file.absolutePath)))
                    .put("r", NodeId("a"), NodeState(NodeStatus.SUCCEEDED, Branch.ELSE))
            }
            val reopened = SqlDelightRunStore(WorkflowDatabase(jvmWorkflowDriver(file.absolutePath)))
            val state = runBlocking { reopened.get("r", NodeId("a")) }
            assertEquals(NodeState(NodeStatus.SUCCEEDED, Branch.ELSE), state)
        } finally {
            file.delete()
        }
    }
}
