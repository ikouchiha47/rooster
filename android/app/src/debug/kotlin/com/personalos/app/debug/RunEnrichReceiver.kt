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
 * Debug-only trigger that runs enrichment through whatever [AppContainer.enrichment]
 * is bound to — the enrich→retag workflow, or the direct call — so the P3 cutover
 * can be exercised without waiting on the scheduler.
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.RUN_ENRICH \
 *     -n com.personalos.app/.debug.RunEnrichReceiver
 * ```
 *
 * The log line names the path and the count, which is the point: the two paths must
 * report the same `+N` and the same progress.
 */
class RunEnrichReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = AppContainer(context.applicationContext)
                val path = if (container.workflowPrefs.enrichViaWorkflow()) "workflow" else "direct"
                val enriched =
                    container.enrichment.run { done, total ->
                        Log.i(TAG, "progress $done/$total")
                    }
                Log.i(TAG, "enrichment via $path: +$enriched")
            } catch (e: Exception) {
                Log.w(TAG, "enrichment failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "RunEnrich"
        const val ACTION = "com.personalos.app.RUN_ENRICH"
    }
}
