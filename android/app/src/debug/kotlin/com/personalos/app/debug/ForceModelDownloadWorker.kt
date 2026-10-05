package com.personalos.app.debug

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personalos.app.core.AppContainer
import com.personalos.app.data.KnownModels

/**
 * Debug-only forced model fetch: downloads the pinned embedding bundle through
 * the same [com.personalos.app.data.ModelDownloader] the Settings screen uses,
 * then loads it — so a device check does not depend on tapping through the UI.
 *
 * Lives in the `debug` source set, so a release APK contains neither it nor its
 * action.
 */
class ForceModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        val downloaded =
            runCatching { container.modelDownloader.download(KnownModels.embeddingGemmaBundle) }
                .onFailure { Log.w(TAG, "download failed", it) }
                .getOrDefault(false)
        val upgraded =
            if (downloaded) {
                runCatching { container.upgradeTagger() }
                    .onFailure { Log.w(TAG, "tagger upgrade failed", it) }
                    .getOrDefault(false)
            } else {
                false
            }
        Log.i(TAG, "models downloaded=$downloaded, tagger upgraded=$upgraded")
        return Result.success()
    }

    companion object {
        const val TAG = "ForceModelDownload"
    }
}
