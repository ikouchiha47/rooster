package com.personalos.app.data.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Start/stop a registered [com.personalos.app.core.work.BackgroundTask] in the
 * foreground. The reusable front door — call it from a button, a receiver, anywhere;
 * it does not know *what* the task does.
 *
 * Call [start] from the **foreground** (a user action): Android 12+ forbids starting
 * a `dataSync` foreground service from the background.
 */
object ForegroundTasks {
    fun start(
        context: Context,
        taskId: String,
    ) {
        val intent =
            Intent(context, ForegroundTaskService::class.java)
                .putExtra(ForegroundTaskService.EXTRA_TASK_ID, taskId)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, ForegroundTaskService::class.java))
    }
}
