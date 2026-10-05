package com.personalos.app.data.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personalos.app.core.AppContainer
import kotlinx.coroutines.CancellationException

/**
 * Re-tags **one source family**, in the background, as its own job.
 *
 * One family per job so a slow, large family cannot hold up a small one (SMS), and
 * so each can be cancelled, retried and inspected independently.
 *
 * Versioned: the job carries [BackfillVersion.KEY]; if it is not the current
 * version it exits **before loading the model**, so a superseded run never becomes
 * a second model load. It is re-checked after the load, to stop promptly.
 */
class RetagWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val family = inputData.getString(KEY_FAMILY) ?: return Result.failure()
        val version = inputData.getLong(BackfillVersion.KEY, 0L)
        if (!BackfillVersion.isCurrent(applicationContext, version)) {
            Log.i(TAG, "retag $family superseded (v$version)")
            return Result.success()
        }
        val container = AppContainer.get(applicationContext)
        return try {
            // Idempotent and shared: the first family to run loads the model, the
            // rest find it ready. Order across families therefore does not matter.
            container.upgradeTagger()
            if (!BackfillVersion.isCurrent(applicationContext, version)) {
                Log.i(TAG, "retag $family superseded after load")
                return Result.success()
            }
            val tagged = container.retagger.run(family)
            Log.i(TAG, "retag $family: +$tagged tags")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "retag $family failed", e)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "RetagWorker"
        const val KEY_FAMILY = "family"
    }
}
