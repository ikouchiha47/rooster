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
 * Debug-only trigger that sends through the **real** sender path, so the sent-result
 * receiver can be exercised without tapping through Compose.
 *
 * ```
 * adb shell am broadcast -a com.personalos.app.SEND_SMS \
 *     -n com.personalos.app/.debug.SendSmsReceiver \
 *     --es to 9999999999 --es body "test"
 * ```
 *
 * Logs the [com.personalos.app.core.sms.SmsSendResult] and how long it took. A
 * working receiver answers in seconds; a broken one waits out the 60s timeout.
 */
class SendSmsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val to = intent.getStringExtra(EXTRA_TO) ?: return
        val body = intent.getStringExtra(EXTRA_BODY) ?: "personalos test"
        // -1 (absent) exercises the default manager; a real sub id exercises the
        // SIM-selected path the UI uses.
        val sub = intent.getIntExtra(EXTRA_SUB, -1).takeIf { it >= 0 }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = AppContainer(context.applicationContext)
                val started = System.currentTimeMillis()
                val result = container.smsSender.send(to, body, sub)
                Log.i(TAG, "send to $to -> $result in ${System.currentTimeMillis() - started}ms")
            } catch (e: Exception) {
                Log.w(TAG, "send to $to threw", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "SendSms"
        const val ACTION = "com.personalos.app.SEND_SMS"
        const val EXTRA_TO = "to"
        const val EXTRA_BODY = "body"
        const val EXTRA_SUB = "sub"
    }
}
