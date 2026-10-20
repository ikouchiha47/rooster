package com.personalos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personalos.app.core.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only adb trigger for loading the tagger, deliberately **without**
 * WorkManager — so a device check of "does the model load?" is not at the mercy
 * of the job scheduler.
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.UPGRADE_TAGGER \
 *     -n com.personalos.app/.debug.UpgradeTaggerReceiver
 * ```
 *
 * Debug-only, so a release APK contains neither the class nor the action.
 */
class UpgradeTaggerReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        // onReceive must return quickly; finish the async work through goAsync.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val upgraded = AppContainer(context.applicationContext).upgradeTagger()
                Log.i(TAG, "upgradeTagger=$upgraded")
            } catch (t: Throwable) {
                Log.w(TAG, "upgradeTagger failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "UpgradeTagger"
        const val ACTION = "com.personalos.app.UPGRADE_TAGGER"
    }
}
