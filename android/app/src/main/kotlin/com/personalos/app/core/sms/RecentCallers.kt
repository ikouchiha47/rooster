package com.personalos.app.core.sms

/** One number's call history, condensed: when, and which kinds happened. */
data class CallInteraction(
    val number: String,
    val lastAt: Long,
    val attended: Boolean,
    val missed: Boolean,
    val accountId: String? = null,
)

/**
 * Call-log numbers for the picker's Attended/Missed tabs — a separate port from
 * [ContactDirectory], because call history and the address book are different
 * facts with different permissions.
 */
interface RecentCallers {
    /** Normalised numbers with any call, most recent first. Empty when denied. */
    suspend fun interactions(): List<CallInteraction>
}
