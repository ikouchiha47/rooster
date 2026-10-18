package com.personalos.app.core.sms

/**
 * The one seam for sending an SMS. [AndroidSmsSender] is the device-backed
 * implementation; the UI talks to this, never to `SmsManager`.
 *
 * The contract is total: [send] returns a [SmsSendResult] for **every** input —
 * a denied permission, an empty destination or body, or a timeout all come back
 * as [SmsSendResult.Failed], never as a thrown exception and never as a silent
 * success (docs/CODE-DESIGN-GUIDELINES.md §3).
 */
interface SmsSender {
    suspend fun send(
        destination: String,
        body: String,
        subscriptionId: Int? = null,
    ): SmsSendResult
}

/** The outcome of a [SmsSender.send] call. */
sealed interface SmsSendResult {
    data object Sent : SmsSendResult

    data class Failed(
        val reason: String,
    ) : SmsSendResult
}

/** One address-book entry: a display [name] and a sendable [number]. */
data class Contact(
    val name: String,
    val number: String,
)

/**
 * The one read for the send picker's address book. Returns the full set; the
 * caller decides how many to show (docs/CODE-DESIGN-GUIDELINES.md §1).
 */
interface ContactDirectory {
    suspend fun all(): List<Contact>
}
