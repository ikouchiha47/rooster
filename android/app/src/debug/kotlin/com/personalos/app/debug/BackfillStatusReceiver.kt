package com.personalos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import com.personalos.app.data.work.SyncScheduler

/**
 * Debug-only: answers "what is the backfill doing right now?" from WorkManager's own
 * record, so state is read rather than guessed from logs.
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.BACKFILL_STATUS \
 *     -n com.personalos.app/.debug.BackfillStatusReceiver
 * ```
 *
 * Debug-only, so a release APK has neither the class nor the action.
 */
class BackfillStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val workManager = WorkManager.getInstance(context.applicationContext)
        runCatching {
            val infos = workManager.getWorkInfosByTag(SyncScheduler.BACKFILL_TAG).get()
            if (infos.isEmpty()) Log.i(TAG, "backfill status: no work")
            infos.forEach { info ->
                Log.i(
                    TAG,
                    "backfill status: state=${info.state} attempts=${info.runAttemptCount} tags=${info.tags}",
                )
            }
        }.onFailure { Log.w(TAG, "status failed", it) }
    }

    companion object {
        const val TAG = "BackfillStatus"
        const val ACTION = "com.personalos.app.BACKFILL_STATUS"
    }
}
