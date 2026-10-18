package com.personalos.app.data

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.personalos.app.core.sms.SmsSendResult
import com.personalos.app.core.sms.SmsSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Sends SMS through the platform [SmsManager].
 *
 * Every failure is a [SmsSendResult.Failed], never a throw: no permission, an
 * empty destination/body, a radio failure reported by a part, and a timeout all
 * have their own reason. A long body is split with `divideMessage` and sent
 * multipart, so it is never truncated.
 *
 * The result of a send is asynchronous — the platform broadcasts one
 * `sentIntent` per part — so the call suspends on a [CompletableDeferred] until
 * the last part reports. A receiver is registered per send under a **unique
 * action**, so two concurrent sends cannot observe each other's results.
 */
class AndroidSmsSender(
    private val context: Context,
    /**
     * Whether the SEND_SMS permission is held. Injectable so tests do not need a
     * real grant; defaults to reading the platform permission at call time, so a
     * grant made after construction is honoured.
     */
    private val permitted: () -> Boolean = {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS,
        ) == PackageManager.PERMISSION_GRANTED
    },
) : SmsSender {
    override suspend fun send(
        destination: String,
        body: String,
        subscriptionId: Int?,
    ): SmsSendResult {
        if (destination.isBlank()) return SmsSendResult.Failed("invalid")
        if (body.isBlank()) return SmsSendResult.Failed("invalid")
        if (!permitted()) return SmsSendResult.Failed("permission")
        return withContext(Dispatchers.IO) { dispatch(destination, body, subscriptionId) }
    }

    /**
     * Posts the message and waits for the platform's per-part result. All failure
     * paths return; the receiver is always unregistered.
     */
    private suspend fun dispatch(
        destination: String,
        body: String,
        subscriptionId: Int?,
    ): SmsSendResult {
        val parts =
            runCatching { smsManager(subscriptionId).divideMessage(body) }
                .getOrNull()
                .orEmpty()
        if (parts.isEmpty()) return SmsSendResult.Failed("invalid")

        val action = "$ACTION_PREFIX${UUID.randomUUID()}"
        val result = CompletableDeferred<SmsSendResult>()
        val receiver = SentResultReceiver(action, parts.size, result)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        return try {
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.forEachIndexed { index, _ ->
                sentIntents +=
                    PendingIntent.getBroadcast(
                        context,
                        index,
                        Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
            }
            smsManager(subscriptionId).sendMultipartTextMessage(destination, null, ArrayList(parts), sentIntents, null)
            withTimeoutOrNull(TIMEOUT_MS) { result.await() } ?: SmsSendResult.Failed("timeout")
        } catch (_: Exception) {
            SmsSendResult.Failed("send_failed")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * API 31 moved `getDefault()` behind a typed service lookup; below 31 the
     * static factory is still the only way.
     */
    private fun smsManager(subscriptionId: Int?): SmsManager =
        when {
            subscriptionId != null -> SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> context.getSystemService(SmsManager::class.java)
            else -> {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        }

    /**
     * One broadcast per part carries the part's result code. Completion happens on
     * the last part, or early on the first failure — either way exactly once, and
     * only for the action this send registered.
     */
    private class SentResultReceiver(
        private val action: String,
        private val partCount: Int,
        private val result: CompletableDeferred<SmsSendResult>,
    ) : BroadcastReceiver() {
        private var received = 0
        private var failure: String? = null

        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            if (intent.action != action) return
            val complete: SmsSendResult?
            synchronized(this) {
                if (result.isCompleted) return
                received++
                if (resultCode != Activity.RESULT_OK && failure == null) {
                    failure = "send_failed:$resultCode"
                }
                complete =
                    when {
                        failure != null -> SmsSendResult.Failed(failure!!)
                        received >= partCount -> SmsSendResult.Sent
                        else -> null
                    }
            }
            if (complete != null) result.complete(complete)
        }
    }

    private companion object {
        /** Not `setPackage`-scoped alone: a distinct action keeps sends separate. */
        const val ACTION_PREFIX = "com.personalos.app.SMS_SENT."

        /** How long to wait for the platform's per-part result before giving up. */
        const val TIMEOUT_MS = 60_000L
    }
}
