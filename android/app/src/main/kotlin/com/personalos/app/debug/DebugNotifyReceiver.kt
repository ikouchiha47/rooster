package com.personalos.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.personalos.app.BuildConfig
import com.personalos.app.core.notify.AppNotifications
import com.personalos.app.core.notify.Notification
import com.personalos.app.core.notify.NotificationKind
import com.personalos.app.core.notify.Severity

/**
 * Debug-only: post a notification from adb, so the host can be seen without
 * waiting for a real sync or a rule fire. A no-op in release builds.
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.DEBUG_NOTIFY \
 *   --es body "hello from adb" --es severity WARNING --el ttl 12000
 * ```
 *
 * It posts to the process-wide [AppNotifications], the same list the UI renders,
 * and `ttl` (ms) clears it automatically; omit `ttl` (or pass 0) for it to stay.
 */
class DebugNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!BuildConfig.DEBUG) return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Debug"
        val body = intent.getStringExtra(EXTRA_BODY)
        val severity =
            runCatching { Severity.valueOf(intent.getStringExtra(EXTRA_SEVERITY) ?: "INFO") }
                .getOrDefault(Severity.INFO)
        val ttl = intent.getLongExtra(EXTRA_TTL, 0L).takeIf { it > 0 }
        Log.i(TAG, "post title=$title body=$body severity=$severity ttl=$ttl")
        AppNotifications.post(
            Notification(
                id = "debug",
                kind = NotificationKind.RULE_FIRE,
                severity = severity,
                title = title,
                body = body,
                ttlMs = ttl,
                at = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val ACTION = "com.personalos.app.DEBUG_NOTIFY"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_SEVERITY = "severity"
        const val EXTRA_TTL = "ttl"
        private const val TAG = "DebugNotify"
    }
}
