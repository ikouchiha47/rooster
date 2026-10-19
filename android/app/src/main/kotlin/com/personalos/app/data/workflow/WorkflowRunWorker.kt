package com.personalos.app.data.workflow

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personalos.app.core.AppContainer
import com.personalos.workflow.RunStatus
import kotlinx.coroutines.CancellationException

/**
 * Hosts one workflow run on Android: WorkManager is the durable host, the engine
 * does the walking (ADR 0009 §5). The `runId` comes from the input, not a fresh
 * value per attempt, so a killed-then-retried worker resumes the same run.
 *
 * A paused run retries (backpressure is not failure); a failed run retries a
 * bounded number of times, then gives up. The engine's own `RunStore` makes the
 * retries skip nodes that already completed.
 */
class WorkflowRunWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(KEY_WORKFLOW) ?: return Result.failure()
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.failure()
        return try {
            val result = AppContainer.get(applicationContext).workflowRuns.run(workflowId, runId)
            Log.i(TAG, "workflow $workflowId ($runId): ${result.status}")
            when (result.status) {
                RunStatus.SUCCEEDED -> Result.success()
                // A run reported as still RUNNING means another host owns it.
                RunStatus.RUNNING, RunStatus.PAUSED -> Result.retry()
                RunStatus.FAILED -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "workflow $workflowId failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val TAG = "WorkflowRun"
        const val KEY_WORKFLOW = "workflow"
        const val KEY_RUN_ID = "runId"
        private const val MAX_ATTEMPTS = 3
    }
}
