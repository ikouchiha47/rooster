package com.personalos.app.data

import com.personalos.app.core.sms.SmsSendResult
import com.personalos.app.core.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The outbox: a send writes its row **now** (`queued`) and returns; the platform's
 * result arrives whenever it does — measured at ~60 s on a weak signal — and moves
 * that same row to `sent` or `failed`.
 *
 * This is the fix for "stuck in Sending": the screen no longer waits on the
 * platform report, so a slow report cannot masquerade as failure, and the message
 * no longer gets resent out of frustration. A failure is a *transition*, never a
 * second row.
 */
class SmsOutbox(
    private val sender: SmsSender,
    private val ingest: SmsIngest,
    private val scope: CoroutineScope,
    /** The subscription the send will actually use, so the row can carry it. */
    private val resolve: (Int?) -> Int? = { it },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Non-blocking. Enqueues the row, fires the send, marks the result out of band.
     * The row is keyed by the shared send identity, so a provider row for the same
     * send collides instead of duplicating.
     */
    fun enqueue(
        destination: String,
        body: String,
        subscriptionId: Int?,
    ) {
        val at = clock()
        val sub = resolve(subscriptionId)
        scope.launch {
            val key = runCatching { ingest.enqueueSent(destination, body, sub, at) }.getOrNull() ?: return@launch
            val result = runCatching { sender.send(destination, body, sub) }.getOrNull()
            runCatching { ingest.markResult(key, result is SmsSendResult.Sent) }
        }
    }
}
