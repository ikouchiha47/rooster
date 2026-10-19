package com.personalos.mlsdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Downloads models without the app having to stay open.
 *
 * A plain foreground service, not WorkManager: the work is one long transfer with
 * a progress bar the user is watching, and a 176 MB file must not be killed
 * because a screen went dark. The service starts foreground *immediately*, holds
 * a CPU wake lock only for the transfer (capped, so a bug cannot hold it for
 * ever), and resumes from the `.part` file on a retry ([HttpDownloader]).
 *
 * Progress goes two ways: [DownloadEvents] for in-app observers, and the
 * notification for everyone else. The notification is **throttled separately**
 * from the event stream, because Android rate-limits `notify()` per app and will
 * silently shed the final "complete" update if it is spammed — a real device
 * finding, not a precaution.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val artifacts = intent?.getStringExtra(EXTRA_ARTIFACTS)?.let(Manifest::parse)?.artifacts
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Downloading models"
        if (artifacts.isNullOrEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Must be foreground promptly, so start it before any work.
        startForegroundCompat(title)
        acquireWakeLock()

        job =
            scope.launch {
                try {
                    runDownload(artifacts, title)
                } finally {
                    releaseWakeLock()
                    stopForegroundCompat()
                    stopSelf(startId)
                }
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private suspend fun runDownload(
        artifacts: List<Artifact>,
        title: String,
    ) {
        val hub = Hub(this, artifacts)
        var failed: String? = null
        for (artifact in artifacts) {
            var current = artifact.name
            var lastPct = -1
            var lastNotifyAt = 0L
            val ok =
                runCatching {
                    hub.ensure(artifact.ref) { file, written, total ->
                        current = file.fileName
                        DownloadEvents.emit(
                            DownloadEvents.Update(artifact.name, file.fileName, written, total),
                        )
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                val now = System.currentTimeMillis()
                                if (now - lastNotifyAt >= NOTIFY_INTERVAL_MS) {
                                    lastNotifyAt = now
                                    notifyProgress(title, "${artifact.name}/${file.fileName}", pct)
                                }
                            }
                        }
                    }
                }.onFailure { error ->
                    Log.w(TAG, "download failed: ${artifact.name}/$current", error)
                    DownloadEvents.emit(
                        DownloadEvents.Update(
                            artifactName = artifact.name,
                            fileName = current,
                            written = 0,
                            total = artifact.sizeBytes,
                            done = true,
                            error = error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            if (ok.isSuccess) {
                DownloadEvents.emit(
                    DownloadEvents.Update(
                        artifactName = artifact.name,
                        fileName = current,
                        written = artifact.sizeBytes,
                        total = artifact.sizeBytes,
                        done = true,
                    ),
                )
            } else {
                failed = "${artifact.name}/$current"
                break
            }
        }
        notifyFinished(title, failed)
    }

    // ---------------------------------------------------------------- notification

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Artifact downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading models")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)
            .build()

    private fun startForegroundCompat(title: String) {
        val notification = buildNotification(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyProgress(
        title: String,
        fileName: String,
        percent: Int,
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification =
            Notification
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText("$fileName · $percent%")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, false)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyFinished(
        title: String,
        failed: String?,
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val done =
            Notification
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(
                    if (failed == null) {
                        android.R.drawable.stat_sys_download_done
                    } else {
                        android.R.drawable.stat_notify_error
                    },
                ).setContentTitle(title)
                .setContentText(if (failed == null) "Download complete" else "Failed: $failed")
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        manager.notify(NOTIFICATION_ID, done)
    }

    /** Tapping the finished notification opens the app; no deep link is assumed. */
    private fun openAppIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ---------------------------------------------------------------- wake lock

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mlsdk:model-download").apply {
                setReferenceCounted(false)
                // Capped: a missed release must never hold the CPU for ever.
                acquire(WAKE_LOCK_CAP_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "mlsdk_model_download"
        private const val NOTIFICATION_ID = 9110
        private const val NOTIFY_INTERVAL_MS = 400L
        private const val WAKE_LOCK_CAP_MS = 30L * 60L * 1000L

        /**
         * The artifacts to fetch, as a registry document — an `Artifact` is not
         * `Parcelable`, and the registry is the one serialisation we already
         * guarantee.
         */
        const val EXTRA_ARTIFACTS = "artifacts"
        const val EXTRA_TITLE = "title"

        /**
         * Starts the service for [artifacts]. Returns false when the platform refuses
         * to start a foreground service (e.g. background-start restrictions), so a
         * caller can fall back to an in-process fetch instead of silently doing
         * nothing.
         */
        fun start(
            context: Context,
            artifacts: Collection<Artifact>,
            title: String = "Downloading models",
        ): Boolean =
            runCatching {
                context.startForegroundService(
                    Intent(context, DownloadService::class.java)
                        .putExtra(EXTRA_ARTIFACTS, Manifest.write(ArtifactRegistry.of(artifacts)))
                        .putExtra(EXTRA_TITLE, title),
                )
                true
            }.getOrDefault(false)
    }
}
