package com.personalos.app.core.feed

import kotlinx.serialization.Serializable

/**
 * Health of one feed. Persisted, so the Sources screen still shows the last
 * known state after a process restart instead of a blank list.
 */
@Serializable
data class FeedStatus(
    val id: String,
    val name: String,
    val url: String,
    val lastAttemptAt: Long = 0L,
    val lastOkAt: Long = 0L,
    val ok: Boolean = false,
    val lastError: String? = null,
    val itemCount: Int = 0,
    /** HTTP status of the last attempt; null when the socket never answered. */
    val statusCode: Int? = null,
) {
    val neverSynced: Boolean get() = lastAttemptAt == 0L

    val host: String
        get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")
}
