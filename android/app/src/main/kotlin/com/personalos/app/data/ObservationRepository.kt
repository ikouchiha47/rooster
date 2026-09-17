package com.personalos.app.data

import com.personalos.app.core.rules.FieldValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * ADR 0005 T19: tiles read the store. The latest observation per identity plus
 * its fields — one owner (`events` + `item_fields`), no parallel provider
 * list. Empty store renders placeholder, never a borrowed cache value.
 */
data class ObservationView(
    val source: String,
    val timestamp: Long,
    val title: String,
    val fields: Map<String, FieldValue>,
)

class ObservationRepository(
    private val eventDao: EventDao,
    private val fieldDao: ItemFieldDao,
) {
    suspend fun latest(sourceId: String): ObservationView? {
        val row = eventDao.latestObservation(sourceId) ?: return null
        return viewOf(row)
    }

    fun observeLatest(sourceId: String): Flow<ObservationView?> =
        combine(eventDao.observeLatestObservation(sourceId), kotlinx.coroutines.flow.flowOf(Unit)) { row, _ ->
            row?.let { runCatching { viewOf(it) }.getOrNull() }
        }

    suspend fun latestMany(sourceIds: List<String>): List<ObservationView> {
        if (sourceIds.isEmpty()) return emptyList()
        val rows = eventDao.latestObservations(sourceIds)
        val latestBySource = rows.groupBy { it.source }.mapValues { (_, group) -> group.maxBy { it.timestamp } }
        return latestBySource.values.mapNotNull { runCatching { viewOf(it) }.getOrNull() }
    }

    private suspend fun viewOf(row: EventEntity): ObservationView {
        val fields =
            fieldDao
                .forItem(row.ulid)
                .mapNotNull { entity ->
                    entity.toFieldValue()?.let { entity.name to it }
                }.toMap()
        return ObservationView(source = row.source, timestamp = row.timestamp, title = row.title, fields = fields)
    }
}
