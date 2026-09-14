package com.personalos.app.core.cache

/**
 * Tiny keyed string cache with a write time, so providers can serve stale data
 * instantly and refresh only when it is old enough. Keeps network calls off the
 * screen-open path and stops the UI appearing late.
 */
interface StringCache {
    data class Entry(
        val value: String,
        val at: Long,
    )

    fun read(key: String): Entry?

    fun write(
        key: String,
        value: String,
        at: Long,
    )
}
