package com.personalos.app.data

import com.personalos.app.core.notify.Notification
import com.personalos.app.core.notify.NotificationCenter
import com.personalos.app.core.notify.NotificationKind
import com.personalos.app.core.notify.Severity
import com.personalos.mlsdk.ArtifactStatus
import com.personalos.mlsdk.DownloadEvents
import com.personalos.mlsdk.Hub
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches artifacts through the SDK in the **foreground service**, so a 176 MB
 * transfer survives the app being backgrounded, and mirrors the service's progress
 * onto the global notification host — the same host a sync uses.
 *
 * It is deliberately thin: the SDK owns where the files go, whether they are
 * valid, and how to resume. This only turns "downloading 43%" into something the
 * user can see, and a failure into a short-lived error.
 */
class ModelDownloader(
    private val hub: Hub,
    private val notifications: NotificationCenter,
) {
    /** Starts the fetch for [refs]; true only when every artifact ends up present. */
    suspend fun download(refs: Collection<String>): Boolean =
        withContext(Dispatchers.IO) {
            val started = mutableSetOf<String>()
            coroutineScope {
                // Subscribe *before* the service starts. The event flow has no replay,
                // and a 404 fails in milliseconds — a `done` emitted before the
                // collector attached was lost, leaving the caller waiting the full
                // timeout with the button stuck on "DOWNLOADING".
                val mirror =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        DownloadEvents.updates.collect { update ->
                            val id = "artifact:${update.artifactName}"
                            when {
                                update.done && update.error == null -> {
                                    notifications.dismiss(id)
                                }

                                update.done -> {
                                    notifications.post(
                                        Notification(
                                            id = "artifact-error",
                                            kind = NotificationKind.ERROR,
                                            severity = Severity.ERROR,
                                            title = "Download failed",
                                            body = "${update.artifactName}: ${update.error}",
                                            ttlMs = 8_000,
                                            at = System.currentTimeMillis(),
                                        ),
                                    )
                                }

                                else -> {
                                    val percent = if (update.total > 0) (update.written * 100 / update.total).toInt() else 0
                                    if (started.add(update.artifactName)) {
                                        notifications.post(
                                            Notification(
                                                id = id,
                                                kind = NotificationKind.MODEL,
                                                severity = Severity.INFO,
                                                title = "Downloading",
                                                body = update.fileName,
                                                progress = true,
                                                at = System.currentTimeMillis(),
                                            ),
                                        )
                                    } else {
                                        notifications.update(id, "${update.fileName} $percent%")
                                    }
                                }
                            }
                        }
                    }
                // The service does the transfer; a refusal to start means the caller
                // should fall back to an in-process fetch instead of doing nothing.
                val accepted = hub.download(refs)
                // Completion is the files' state, not an event: `ensure` skips files
                // that are already READY without emitting anything, so an event-only
                // wait would never end for a partially complete artifact.
                if (accepted) {
                    withTimeoutOrNull(TIMEOUT_MS) {
                        while (refs.any { hub.status(it) != ArtifactStatus.READY }) delay(POLL_MS)
                    }
                }
                mirror.cancel()
                accepted && refs.all { hub.status(it) == ArtifactStatus.READY }
            }
        }

    private companion object {
        const val POLL_MS = 200L
        const val TIMEOUT_MS = 30L * 60_000L
    }
}
