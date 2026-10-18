package com.personalos.app.ui.messages

import com.personalos.app.core.sms.SmsSendResult

/**
 * How a compose send is addressed. One mode now, but modelled explicitly so
 * group threads are a later *behaviour* added here — not a restructure of the
 * compose screen.
 *
 * [available] is the honesty flag: `GroupMms` is present in the model and shown
 * in the picker, deliberately greyed and annotated, so a reader sees the path
 * rather than a control that would silently do the wrong thing.
 */
enum class RecipientMode(
    val label: String,
    val available: Boolean,
) {
    Sms("SMS", true),
    GroupMms("Group MMS", false),
    ;

    companion object {
        /** The stored route value back to a mode; anything unknown is plain SMS. */
        fun of(name: String?): RecipientMode = entries.firstOrNull { it.name == name } ?: Sms
    }
}

/**
 * One recipient's state in a fan-out send. Each is tracked on its own, so a
 * failure never hides a recipient that succeeded and never aborts the rest.
 */
sealed interface SendStatus {
    data object Pending : SendStatus

    data object Sent : SendStatus

    data class Failed(
        val reason: String,
    ) : SendStatus {
        /** The raw reason is a code; this is the plain line the row shows. */
        val detail: String get() = failureText(reason)
    }

    /** The row's plain-language line. No exclamation, no jargon. */
    val label: String
        get() =
            when (this) {
                Pending -> "Pending"
                Sent -> "Sent"
                is Failed -> "Failed: $detail"
            }

    companion object {
        /** A send result as the row's status. */
        fun of(result: SmsSendResult): SendStatus =
            when (result) {
                SmsSendResult.Sent -> Sent
                is SmsSendResult.Failed -> Failed(result.reason)
            }
    }
}

/**
 * The send seam's reason codes are terse (`permission`, `send_failed:31`). This
 * is the one place they become a sentence a person can act on.
 */
fun failureText(reason: String): String =
    when {
        reason == "permission" -> "sending is not permitted"
        reason == "invalid" -> "number or message is unusable"
        reason == "timeout" -> "no confirmation from the network"
        reason == "send_failed" -> "the network rejected it"
        reason.startsWith("send_failed:") -> "the network rejected it (code ${reason.substringAfter(':')})"
        else -> reason
    }
