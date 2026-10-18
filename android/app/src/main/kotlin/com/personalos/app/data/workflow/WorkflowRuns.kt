package com.personalos.app.data.workflow

import android.content.Context
import com.personalos.workflow.Engine
import com.personalos.workflow.NodeId
import com.personalos.workflow.RunResult
import com.personalos.workflow.RunStore
import com.personalos.workflow.Workflow
import com.personalos.workflow.WorkflowStep
import com.personalos.workflow.db.SqlDelightRunStore
import com.personalos.workflow.db.WorkflowDatabase
import com.personalos.workflow.db.androidWorkflowDriver

/**
 * The app's workflow entry point: a registry of workflows plus the engine wired to
 * the steps and a durable [RunStore].
 *
 * Workflows are **data** (a map), so running one is a lookup, never a
 * `when (workflowId)` (ADR 0009). `prepare` is the one thing that must happen
 * before a run — upgrading the tagger — and it lives here so every caller gets it.
 */
class WorkflowRuns(
    private val workflows: Map<String, Workflow>,
    steps: Map<String, WorkflowStep>,
    store: RunStore,
    private val prepare: suspend () -> Unit = {},
) {
    private val engine = Engine(steps = steps, store = store)

    fun known(id: String): Boolean = workflows.containsKey(id)

    suspend fun run(
        id: String,
        runId: String,
        onProgress: suspend (node: NodeId, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): RunResult {
        val workflow = workflows[id] ?: error("unknown workflow: $id")
        prepare()
        return engine.run(workflow, runId, onProgress)
    }
}

/**
 * One [WorkflowDatabase] per process, opened once through the Android SQLite
 * driver. Workers build a fresh `AppContainer` each run; sharing the database
 * here keeps them from opening (and leaking) a driver per run.
 */
internal object WorkflowStore {
    @Volatile private var database: WorkflowDatabase? = null

    fun get(context: Context): WorkflowDatabase =
        database ?: synchronized(this) {
            database ?: WorkflowDatabase(androidWorkflowDriver(context.applicationContext)).also { database = it }
        }

    fun store(context: Context): RunStore = SqlDelightRunStore(get(context))
}
