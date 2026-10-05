package com.personalos.app.core.sync

import android.util.Log
import com.personalos.app.core.notify.Notification
import com.personalos.app.core.notify.NotificationCenter
import com.personalos.app.core.notify.NotificationKind
import com.personalos.app.core.notify.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The **producer** of the sync notification. It no longer holds the banner's
 * state itself (which is why the bar only existed where a tile scaffold did) —
 * it posts a [Notification] to the shared [NotificationCenter] and updates it as
 * steps advance. The host decides how it looks.
 *
 * No single screen can own "something is syncing": scheduled workers sync while
 * no screen is open, and several tiles trigger the same ingest. One producer in
 * the container, one center, so the indicator is the same everywhere and every
 * step is also written to `adb logcat -s Sync`.
 */
class SyncStatus(
    private val notifications: NotificationCenter,
) {
    private val _running = MutableStateFlow(false)

    /** True while a sync is in flight, so a caller can refuse a second pass. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Runs [block] as the current sync, unless one is already running — a second
     * tap should not start a competing pass. Posts the sync notification, and on
     * failure leaves a short-lived error notification so a swallowed exception is
     * not silent on screen.
     */
    suspend fun run(
        label: String,
        block: suspend () -> Unit,
    ) {
        if (_running.value) {
            Log.i(TAG, "$label: already syncing, ignoring")
            return
        }
        _running.value = true
        notifications.post(
            Notification(
                id = SYNC_ID,
                kind = NotificationKind.SYNC,
                severity = Severity.INFO,
                title = "Syncing",
                body = label,
                progress = true,
                at = System.currentTimeMillis(),
            ),
        )
        Log.i(TAG, "$label: start")
        val startedAt = System.currentTimeMillis()
        try {
            block()
            Log.i(TAG, "$label: done in ${System.currentTimeMillis() - startedAt}ms")
        } catch (e: Exception) {
            Log.w(TAG, "$label: failed", e)
            notifications.post(
                Notification(
                    id = ERROR_ID,
                    kind = NotificationKind.ERROR,
                    severity = Severity.ERROR,
                    title = "Sync failed",
                    body = e.message ?: label,
                    ttlMs = ERROR_TTL_MS,
                    at = System.currentTimeMillis(),
                ),
            )
        } finally {
            _running.value = false
            notifications.dismiss(SYNC_ID)
        }
    }

    /** Records a step, so the bar and the log agree on what is happening. */
    fun step(text: String) {
        notifications.update(SYNC_ID, text)
        Log.i(TAG, text)
    }

    private companion object {
        const val TAG = "Sync"
        const val SYNC_ID = "sync"
        const val ERROR_ID = "sync-error"
        const val ERROR_TTL_MS = 8_000L
    }
}
