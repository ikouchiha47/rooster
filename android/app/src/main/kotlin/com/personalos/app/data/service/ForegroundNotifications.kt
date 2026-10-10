package com.personalos.app.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.personalos.app.core.work.TaskProgress

/**
 * The ongoing notification a foreground task shows — the "backup in progress" style.
 * One low-importance channel, one id; reused for every task.
 */
object ForegroundNotifications {
    const val CHANNEL = "foreground-task"
    const val ID = 0x7A01

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Background work", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing work like tagging or downloading"
                setShowBadge(false)
            },
        )
    }

    fun build(
        context: Context,
        title: String,
        progress: TaskProgress,
    ): Notification {
        val text =
            when {
                progress.total > 0 -> "${progress.label} ${progress.done}/${progress.total}".trim()
                progress.done > 0 -> "${progress.label} ${progress.done}".trim()
                else -> progress.label.ifBlank { title }
            }
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(progress.total, progress.done, progress.total == 0)
            .build()
    }
}
