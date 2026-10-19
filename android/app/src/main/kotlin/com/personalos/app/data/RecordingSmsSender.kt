package com.personalos.app.data

import com.personalos.app.core.sms.SmsSendResult
import com.personalos.app.core.sms.SmsSender

/**
 * The [SmsSender] the app actually uses: it sends, then records what landed.
 *
 * This app is not the default SMS app, so the platform never writes an outgoing
 * row to `content://sms` for us — meaning the thread would not show our own
 * outbound side unless something records it. The send adapter ([AndroidSmsSender])
 * stays a pure device transport; recording is [SmsIngest]'s job, and this is the
 * one place the two are joined, so no screen has to remember to do it.
 *
 * A record failure must never turn a delivered SMS into a reported failure, so
 * recording is best-effort: the send's own [SmsSendResult] is always returned
 * unchanged.
 */
class RecordingSmsSender(
    private val sender: SmsSender,
    private val ingest: SmsIngest,
    private val resolve: (Int?) -> Int? = { it },
    private val clock: () -> Long = System::currentTimeMillis,
) : SmsSender {
    override suspend fun send(
        destination: String,
        body: String,
        subscriptionId: Int?,
    ): SmsSendResult {
        // Outbox semantics: the row is written before the platform is asked, and the
        // outcome updates it — so a slow result can never look like a lost message.
        val sub = resolve(subscriptionId)
        val key = runCatching { ingest.enqueueSent(destination, body, sub, clock()) }.getOrNull()
        val result = sender.send(destination, body, sub)
        if (key != null) {
            runCatching { ingest.markResult(key, result is SmsSendResult.Sent) }
        }
        return result
    }
}
