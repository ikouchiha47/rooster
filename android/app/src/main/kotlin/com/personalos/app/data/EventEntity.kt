package com.personalos.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personalos.app.core.tag.Ulid
import kotlinx.serialization.json.Json

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["dedupe_key"], unique = true),
        Index(value = ["ulid"], unique = true),
        Index(value = ["category"]),
        Index(value = ["timestamp"]),
    ],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * Stable, sortable identity used by `item_tags` and future export/merge.
     * Not the Room primary key: `id` stays autoincrement for keyset paging.
     */
    @ColumnInfo(name = "ulid") val ulid: String,
    /**
     * Natural dedupe key: a feed item uses its URL, an SMS its provider row id
     * (`sms:<_id>`). Replaces the old `(source, type, timestamp, title)` index,
     * which duplicated every article when `type` was renamed.
     */
    @ColumnInfo(name = "dedupe_key") val dedupeKey: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "type") val type: String,
    /** SmsClass name for SMS; other sources fill in their own categories. */
    @ColumnInfo(name = "category") val category: String = "UNKNOWN",
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "entities") val entities: String = "[]",
    @ColumnInfo(name = "location") val location: String? = null,
    @ColumnInfo(name = "url") val url: String? = null,
    /** Epoch ms when enrichment was attempted. Null = not tried, 0 = failed, >0 = success timestamp. */
    @ColumnInfo(name = "enriched_at") val enrichedAt: Long? = null,
) {
    fun toDomain(): Event {
        val entitiesList = Json { ignoreUnknownKeys = true }.decodeFromString<List<String>>(entities)
        return Event(
            id = id,
            source = source,
            type = type,
            timestamp = timestamp,
            title = title,
            content = content,
            entities = entitiesList,
            location = location,
            url = url,
        )
    }

    companion object {
        fun fromDomain(event: Event): EventEntity =
            EventEntity(
                id = event.id,
                ulid = Ulid.next(),
                dedupeKey = event.url ?: "evt:${event.source}:${event.title}:${event.timestamp}",
                source = event.source,
                type = event.type,
                timestamp = event.timestamp,
                title = event.title,
                content = event.content,
                entities = Json { ignoreUnknownKeys = true }.encodeToString(event.entities),
                location = event.location,
                url = event.url,
                enrichedAt = null,
            )
    }
}
