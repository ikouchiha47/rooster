package com.personalos.app.data.adapters

import android.util.Log
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.tag.Ulid
import com.personalos.app.data.EventDao
import com.personalos.app.data.EventEntity
import com.personalos.app.data.ItemFieldDao
import com.personalos.app.data.RuleItemSeed
import com.personalos.app.data.RuleWriter
import com.personalos.app.data.TagWriter
import com.personalos.app.data.toEntity
import com.personalos.app.data.toRuleItemSeed

/**
 * ADR 0005 T8+T17: gauge observation writes — upsert-by-bucket, latest wins.
 *
 * Bucket key = `{identity}:{bucketStartMs}` stored as `dedupe_key`. The same
 * bucket keeps the same `ulid` and replaces that item's fields (never global
 * `REPLACE`, never `IGNORE` — those belong to counters). A new bucket inserts
 * a fresh observation. Counters keep `INSERT OR IGNORE` + freeze and never
 * call this writer.
 */
class ObservationWriter(
    private val eventDao: EventDao,
    private val fieldDao: ItemFieldDao,
    private val tagWriter: TagWriter,
    private val ruleWriter: RuleWriter,
    private val bucketMs: Long = DEFAULT_BUCKET_MS,
) {
    data class Observation(
        val identity: String,
        val timestamp: Long,
        val title: String,
        val content: String,
        val topicTag: String,
        val fields: Map<String, FieldValue>,
    )

    suspend fun write(
        observation: Observation,
        now: Long = System.currentTimeMillis(),
    ): String {
        val bucketStart = observation.timestamp - (observation.timestamp % bucketMs)
        val dedupeKey = "${observation.identity}:$bucketStart"
        val existing = runCatching { eventDao.byDedupeKey(dedupeKey) }.getOrNull()

        return if (existing == null) {
            val row =
                EventEntity(
                    ulid = Ulid.next(),
                    dedupeKey = dedupeKey,
                    source = observation.identity,
                    type = "observation",
                    category = "OBSERVATION",
                    timestamp = observation.timestamp,
                    title = observation.title,
                    content = observation.content,
                    entities = "[]",
                    location = null,
                    url = null,
                    ingestedAt = now,
                )
            val inserted = eventDao.insertAll(listOf(row))
            if (inserted.firstOrNull() == -1L) {
                // Lost a race with a concurrent bucket write: fall through to update.
                val raced = eventDao.byDedupeKey(dedupeKey)
                if (raced != null) {
                    update(raced, observation)
                    raced.ulid
                } else {
                    row.ulid
                }
            } else {
                afterWrite(row, observation)
                row.ulid
            }
        } else {
            update(existing, observation)
            existing.ulid
        }
    }

    private suspend fun update(
        existing: EventEntity,
        observation: Observation,
    ) {
        runCatching {
            eventDao.updateObservation(
                ulid = existing.ulid,
                timestamp = observation.timestamp,
                title = observation.title,
                content = observation.content,
            )
        }.onFailure { Log.w(TAG, "observation update failed for ${existing.ulid}", it) }
        runCatching {
            val rows = observation.fields.map { (name, value) -> value.toEntity(existing.ulid, name) }
            fieldDao.deleteForItem(existing.ulid)
            fieldDao.replaceAll(rows)
        }.onFailure { Log.w(TAG, "observation field replace failed for ${existing.ulid}", it) }
        runCatching {
            ruleWriter.write(existing.toRuleItemSeed().copy(title = observation.title, content = observation.content))
        }.onFailure { Log.w(TAG, "observation rule eval failed for ${existing.ulid}", it) }
    }

    private suspend fun afterWrite(
        row: EventEntity,
        observation: Observation,
    ) {
        runCatching {
            tagWriter.writeAll(
                listOf(
                    row.ulid to
                        com.personalos.app.core.tag.TagInput(
                            text = "${observation.title}\n${observation.content}",
                            source = com.personalos.app.core.tag.Transport.JSON,
                            declaredTags = setOf(observation.topicTag),
                        ),
                ),
            )
        }.onFailure { Log.w(TAG, "observation tag failed for ${row.ulid}", it) }
        runCatching {
            val rows = observation.fields.map { (name, value) -> value.toEntity(row.ulid, name) }
            fieldDao.replaceAll(rows)
        }.onFailure { Log.w(TAG, "observation field write failed for ${row.ulid}", it) }
        runCatching { ruleWriter.write(RuleItemSeed(itemId = row.ulid, sourceId = row.source, title = row.title, content = row.content)) }
            .onFailure { Log.w(TAG, "observation rule eval failed for ${row.ulid}", it) }
    }

    companion object {
        const val TAG = "Observations"

        /** One observation bucket per hour by default; adapters may narrow it. */
        const val DEFAULT_BUCKET_MS = 3_600_000L
    }
}
