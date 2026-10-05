package com.personalos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Debug-only adb trigger for the embedding-model fetch:
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.DOWNLOAD_MODELS \
 *     -n com.personalos.app/.debug.ForceModelDownloadReceiver
 * ```
 *
 * REPLACE, so a retry is never blocked by a previously pending job. Debug-only,
 * so a release APK has neither the class nor the action.
 */
class ForceModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        Log.i(TAG, "force model download requested")
        val request = OneTimeWorkRequestBuilder<ForceModelDownloadWorker>().build()
        WorkManager
            .getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val TAG = "ForceModelDownload"
        const val ACTION = "com.personalos.app.DOWNLOAD_MODELS"
        private const val UNIQUE_NAME = "force-model-download"
    }
}
