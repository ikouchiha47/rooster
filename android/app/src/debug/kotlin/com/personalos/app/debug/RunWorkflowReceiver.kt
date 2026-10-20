package com.personalos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personalos.app.data.work.SyncScheduler
import com.personalos.app.data.workflow.AppWorkflows

/**
 * Debug-only adb trigger for a workflow run:
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.RUN_WORKFLOW \
 *     -n com.personalos.app/.debug.RunWorkflowReceiver
 * ```
 *
 * Optional string extra `workflow` picks the workflow id; the default is the
 * single-node enrichment workflow. Debug-only, so a release APK has neither the
 * class nor the action.
 */
class RunWorkflowReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val workflow = intent.getStringExtra(EXTRA_WORKFLOW) ?: AppWorkflows.ENRICH
        Log.i(TAG, "workflow run requested: $workflow")
        SyncScheduler.enqueueWorkflow(context.applicationContext, workflow)
    }

    companion object {
        const val TAG = "RunWorkflow"
        const val ACTION = "com.personalos.app.RUN_WORKFLOW"
        const val EXTRA_WORKFLOW = "workflow"
    }
}
