package com.personalos.app.data

import com.personalos.app.core.notify.Notification
import com.personalos.app.core.notify.NotificationCenter
import com.personalos.app.core.notify.NotificationKind
import com.personalos.app.core.notify.Severity
import com.personalos.mlsdk.ModelSpec
import com.personalos.mlsdk.ModelStore

/**
 * Fetches a model bundle through the SDK, reporting progress on the **global
 * notification** so it is visible on any screen — the same host a sync uses.
 *
 * It is deliberately thin: the SDK owns where the file goes and whether it is
 * valid, this only drives the user-facing "downloading… 43%" and turns a failure
 * into a short-lived error. A download that fails leaves nothing behind (the SDK
 * deletes the `.part`), so a retry starts clean.
 */
class ModelDownloader(
    private val store: ModelStore,
    private val notifications: NotificationCenter,
) {
    /** Downloads [specs] in order; true only if every one is present and valid afterwards. */
    suspend fun download(specs: List<ModelSpec>): Boolean {
        for (spec in specs) {
            val id = "model:${spec.fileName}"
            notifications.post(
                Notification(
                    id = id,
                    kind = NotificationKind.MODEL,
                    severity = Severity.INFO,
                    title = "Downloading model",
                    body = spec.fileName,
                    progress = true,
                    at = System.currentTimeMillis(),
                ),
            )
            val ok =
                runCatching {
                    store.ensure(spec) { written, total ->
                        val percent = if (total > 0) (written * 100 / total).toInt() else 0
                        notifications.update(id, "${spec.fileName} $percent%")
                    }
                }.onFailure { android.util.Log.w("ModelDownloader", "download failed: ${spec.fileName}", it) }
                    .isSuccess
            notifications.dismiss(id)
            if (!ok) {
                notifications.post(
                    Notification(
                        id = "model-error",
                        kind = NotificationKind.ERROR,
                        severity = Severity.ERROR,
                        title = "Model download failed",
                        body = spec.fileName,
                        ttlMs = 8_000,
                        at = System.currentTimeMillis(),
                    ),
                )
                return false
            }
        }
        return true
    }
}
