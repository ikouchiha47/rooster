package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.rules.FieldValue

/**
 * Writes typed field rows for ingested items. Mirrors [TagWriter] and
 * [MentionWriter]:
 *  - a failure **never blocks ingest**: the item stays stored and field-less
 *    rather than being dropped;
 *  - rows are append-only. [ItemFieldDao] inserts with `IGNORE` on
 *    `(item_id, name)`, so re-running is safe and the **first write wins** —
 *    which is what "frozen at ingest" means (ADR 0003 §3). A repeat write of a
 *    revised value is deliberately a no-op; there is no update path here.
 */
class FieldWriter(
    private val dao: ItemFieldDao,
) {
    /** Writes one item's fields. Returns the rows actually written. */
    suspend fun write(
        itemId: String,
        fields: Map<String, FieldValue>,
    ): Int = writeAll(listOf(itemId to fields))

    /**
     * Writes a batch; one insert for the whole set. Returns the rows actually
     * written — `IGNORE` returns `-1` for a value already frozen.
     */
    suspend fun writeAll(entries: List<Pair<String, Map<String, FieldValue>>>): Int {
        if (entries.isEmpty()) return 0
        val rows =
            entries.flatMap { (itemId, fields) ->
                fields.map { (name, value) -> value.toEntity(itemId, name) }
            }
        if (rows.isEmpty()) return 0
        return runCatching { dao.insertAll(rows) }
            .onFailure { Log.w(TAG, "field insert failed for ${rows.size} rows", it) }
            .getOrNull()
            ?.count { it != -1L }
            ?: 0
    }

    private companion object {
        const val TAG = "Fields"
    }
}
