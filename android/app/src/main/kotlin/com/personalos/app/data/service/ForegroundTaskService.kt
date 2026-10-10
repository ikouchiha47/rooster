package com.personalos.app.data.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.personalos.app.core.AppContainer
import com.personalos.app.core.work.BackgroundTask
import com.personalos.app.core.work.TaskProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs **any** registered [BackgroundTask] in the foreground, so the OS keeps the
 * process alive instead of deferring or cutting it — the downloader/backup pattern.
 *
 * The service knows only the [BackgroundTask] contract: the task to run is named by
 * [EXTRA_TASK_ID] and resolved from [AppContainer.backgroundTasks]. So the same
 * service runs a re-tag, a download, a backfill — a new use is a new task, no change
 * here (Open/Closed).
 *
 * Started **from the foreground** (a user action), which is what Android 12+ requires
 * for a `dataSync` foreground service; and it implements `onTimeout` so a run that
 * hits the 6h/24h cap stops cleanly.
 */
class ForegroundTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        val task = taskId?.let { AppContainer.get(this).backgroundTasks.get(it) }
        if (task == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ForegroundNotifications.ensureChannel(this)
        startInForeground(task, TaskProgress(done = 0, total = 0, label = task.title))
        scope.launch {
            val ok =
                runCatching { task.run { progress -> update(task, progress) } }
                    .onFailure { Log.w(TAG, "task ${task.id} failed", it) }
                    .getOrDefault(false)
            Log.i(TAG, "task ${task.id} finished ok=$ok")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(
        task: BackgroundTask,
        progress: TaskProgress,
    ) {
        val notification = ForegroundNotifications.build(this, task.title, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ForegroundNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ForegroundNotifications.ID, notification)
        }
    }

    private fun update(
        task: BackgroundTask,
        progress: TaskProgress,
    ) {
        runCatching {
            NotificationManagerCompat
                .from(this)
                .notify(ForegroundNotifications.ID, ForegroundNotifications.build(this, task.title, progress))
        }
    }

    /** Android 15+: the dataSync cap was reached; stop cleanly instead of throwing. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Log.i(TAG, "foreground timeout; stopping")
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "ForegroundTask"
        const val EXTRA_TASK_ID = "task-id"
    }
}
