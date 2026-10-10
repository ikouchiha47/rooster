package com.personalos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personalos.app.data.work.SyncScheduler

/**
 * Debug-only adb trigger for the launch backfill (model upgrade + retag) on
 * demand, instead of waiting on the launch schedule:
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.FORCE_BACKFILL \
 *     -n com.personalos.app/.debug.ForceBackfillReceiver
 * ```
 *
 * Delegates to [SyncScheduler.enqueueBackfill], which bumps the backfill version
 * and `REPLACE`s each unit — so this supersedes any in-flight run (which then sees
 * it is stale and exits before loading the model) rather than racing it. Debug
 * only: a release APK has neither the class nor the action.
 */
class ForceBackfillReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        Log.i(TAG, "force backfill requested")
        SyncScheduler.enqueueBackfill(context.applicationContext, force = true)
    }

    companion object {
        const val TAG = "ForceBackfill"
        const val ACTION = "com.personalos.app.FORCE_BACKFILL"
    }
}
