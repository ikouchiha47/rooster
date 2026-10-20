package com.personalos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personalos.app.data.work.SyncScheduler

/**
 * Debug-only adb trigger for a forced retag walk:
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.FORCE_RETAG \
 *     -n com.personalos.app/.debug.ForceRetagReceiver
 * ```
 *
 * Bumps the backfill version, so an in-flight walk is superseded and every item
 * is replayed through the active tagger. Debug-only, like the other triggers.
 */
class ForceRetagReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        Log.i(TAG, "force retag requested")
        SyncScheduler.enqueueBackfill(context.applicationContext, force = true)
    }

    companion object {
        const val TAG = "ForceRetag"
        const val ACTION = "com.personalos.app.FORCE_RETAG"
    }
}
