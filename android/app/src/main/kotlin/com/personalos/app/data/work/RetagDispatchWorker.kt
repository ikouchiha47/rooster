package com.personalos.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personalos.app.core.AppContainer
import kotlinx.coroutines.CancellationException

/**
 * Runs the **retag dispatcher**: one bounded tick that re-tags several chunks,
 * weighted by family priority. Replaces the six per-family workers, which competed
 * for the model and starved SMS.
 *
 * Bounded, so one run fits an OS scheduling window; if work remains it retries, and
 * the per-family cursors mean nothing is lost between runs.
 */
class RetagDispatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val version = inputData.getLong(BackfillVersion.KEY, 0L)
        if (!BackfillVersion.isCurrent(applicationContext, version)) {
            Log.i(TAG, "retag dispatch superseded (v$version)")
            return Result.success()
        }
        val container = AppContainer.get(applicationContext)
        return try {
            container.upgradeTagger()
            if (!BackfillVersion.isCurrent(applicationContext, version)) return Result.success()
            val more = container.retagDispatcher.tick()
            Log.i(TAG, "retag tick: more=$more")
            if (more) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "retag dispatch failed", e)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "RetagDispatch"
    }
}
