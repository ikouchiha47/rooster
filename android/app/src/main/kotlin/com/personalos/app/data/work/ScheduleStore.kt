package com.personalos.app.data.work

import android.content.Context
import com.personalos.app.data.cache.PrefsStringCache

/**
 * Where the dispatcher keeps each family's **deficit**, so fairness survives across
 * runs, not just within one. Interface so the dispatcher can be tested with a fake.
 */
interface ScheduleStore {
    suspend fun load(): Map<String, Int>

    suspend fun save(deficits: Map<String, Int>)
}

/**
 * Deficits are persisted as one compact string, `family=deficit;…`. '=' is the
 * separator because family names contain ':' (`rss:`, `gnews:`).
 */
internal fun encodeDeficits(deficits: Map<String, Int>): String = deficits.entries.joinToString(";") { "${it.key}=${it.value}" }

internal fun parseDeficits(raw: String): Map<String, Int> =
    raw
        .split(';')
        .mapNotNull { part ->
            val name = part.substringBeforeLast('=', "")
            val value = part.substringAfterLast('=', "").toIntOrNull()
            if (name.isEmpty() || value == null) null else name to value
        }.toMap()

/** The real store: one prefs row, read/written whole. */
class PrefsScheduleStore(
    context: Context,
) : ScheduleStore {
    private val cache = PrefsStringCache(context)

    override suspend fun load(): Map<String, Int> =
        cache
            .read(KEY)
            ?.value
            ?.let(::parseDeficits)
            .orEmpty()

    override suspend fun save(deficits: Map<String, Int>) {
        cache.write(KEY, encodeDeficits(deficits), System.currentTimeMillis())
    }

    private companion object {
        const val KEY = "retag:deficit"
    }
}
