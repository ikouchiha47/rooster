package com.personalos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Debug-only adb trigger for an immediate, cache-bypassing sync:
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.FORCE_SYNC \
 *     -n com.personalos.app/.debug.ForceSyncReceiver
 * ```
 *
 * The receiver does no work itself — `onReceive` must return promptly — it
 * enqueues [ForceSyncWorker], which runs through WorkManager. That is what
 * makes the trigger work whether or not the app is already running (WorkManager
 * starts the process to run the job) and makes it independent of the UI.
 *
 * **Debug only, structurally.** This class *and* its manifest entry live in the
 * `debug` source set, so a release APK has neither the class nor the
 * `com.personalos.app.FORCE_SYNC` intent filter, and the broadcast is never
 * delivered. There is no `if (BuildConfig.DEBUG)` branch to fall through: the
 * trigger is absent rather than disabled.
 *
 * `exported = true` is required for `adb shell am broadcast` to reach a
 * receiver that is not the shell's own package; it is debug-only, so the
 * exposure is a forced refresh, nothing more.
 */
class ForceSyncReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        Log.i(TAG, "force sync requested")
        val request = OneTimeWorkRequestBuilder<ForceSyncWorker>().build()
        WorkManager
            .getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val TAG = "ForceSync"
        const val ACTION = "com.personalos.app.FORCE_SYNC"
        private const val UNIQUE_NAME = "force-sync-now"
    }
}
