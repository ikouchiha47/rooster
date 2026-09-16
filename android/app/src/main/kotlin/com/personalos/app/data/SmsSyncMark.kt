package com.personalos.app.data

import android.content.Context

/**
 * The persisted high-water mark for SMS ingest: the highest provider `_id`
 * already ingested. Persisting it across process starts is what makes a
 * background poll safe — a poll reads only what is newer than the mark, so it
 * cannot re-insert the inbox.
 */
interface SmsSyncMark {
    fun read(): Long

    fun write(id: Long)
}

/** SharedPreferences-backed mark, surviving process restarts. */
class PrefsSmsSyncMark(
    context: Context,
) : SmsSyncMark {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): Long = prefs.getLong(KEY_LAST_SEEN_ID, 0L)

    override fun write(id: Long) {
        prefs.edit().putLong(KEY_LAST_SEEN_ID, id).apply()
    }

    private companion object {
        const val PREFS = "sms_source"
        const val KEY_LAST_SEEN_ID = "last_seen_id"
    }
}
