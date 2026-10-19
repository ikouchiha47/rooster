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
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.personalos.app.core.sms.SmsSendResult
import com.personalos.app.core.sms.SmsSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The subscription a send actually uses: the caller's choice when it names a real
 * SIM, else the first active one, else the platform default. Shared, so the row
 * that records a send carries the **same** SIM the send used — otherwise the
 * thread cannot show which line a message went out on.
 */
internal fun resolveSmsSubscription(
    context: Context,
    subscriptionId: Int?,
): Int? {
    if (subscriptionId != null && subscriptionId > 0) return subscriptionId
    val fromSims =
        runCatching {
            context
                .getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                ?.firstOrNull()
                ?.subscriptionId
        }.getOrNull()
    if (fromSims != null && fromSims > 0) return fromSims
    return runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
        .getOrNull()
        ?.takeIf { it > 0 }
}

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
        // EXPORTED, deliberately. The SMS_SENT broadcast is delivered by the
        // telephony system, i.e. from *another* UID, and since Android 14 a
        // NOT_EXPORTED dynamic receiver never sees it — the send would sit until
        // the timeout and report a false failure. The exposure is bounded: the
        // action is unique per send and the PendingIntent is package-scoped.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_EXPORTED,
        )

        Log.i(TAG, "send to $destination via sub=$subscriptionId: ${parts.size} part(s), action=$action")
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
    private fun smsManager(subscriptionId: Int?): SmsManager {
        val sub = resolvedSubscription(subscriptionId)
        return when {
            sub != null -> SmsManager.getSmsManagerForSubscriptionId(sub)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> context.getSystemService(SmsManager::class.java)
            else -> {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        }
    }

    /**
     * Never send on the subscription-less manager if a real one can be resolved.
     * Measured on a dual-SIM device: the default manager reported **no** sent
     * result at all, so the send sat until the timeout while the SIM-selected path
     * reported (late, but reported). `subscriptionId > 0` because 0 /
     * [SubscriptionManager.INVALID_SUBSCRIPTION_ID] are the "no SIM" sentinels.
     */
    private fun resolvedSubscription(subscriptionId: Int?): Int? = resolveSmsSubscription(context, subscriptionId)

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
            Log.i(TAG, "sent-result action=${intent.action} match=${intent.action == action} code=$resultCode")
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
        const val TAG = "SmsSender"

        /** Not `setPackage`-scoped alone: a distinct action keeps sends separate. */
        const val ACTION_PREFIX = "com.personalos.app.SMS_SENT."

        /** How long to wait for the platform's per-part result before giving up. */
        const val TIMEOUT_MS = 60_000L
    }
}
