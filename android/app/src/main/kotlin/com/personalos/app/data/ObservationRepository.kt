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

    /**
     * The forecast week for a place slug: `weather:<slug>:fc:<date>` rows mapped
     * to day cells, oldest date first. Empty store returns empty — the week
     * renders its honest placeholder, never a borrowed cache.
     */
    suspend fun forecastWeek(placeSlug: String): List<com.personalos.app.core.model.WeatherDay> {
        val rows = eventDao.observationsByPrefix("weather:$placeSlug:fc:")
        if (rows.isEmpty()) return emptyList()
        return rows
            .mapNotNull { row ->
                runCatching {
                    val fields = fieldDao.forItem(row.ulid).mapNotNull { it.toFieldValue()?.let { v -> it.name to v } }.toMap()
                    val max = (fields["temp_max_c"] as? FieldValue.Num)?.value ?: return@runCatching null
                    val min = (fields["temp_min_c"] as? FieldValue.Num)?.value ?: return@runCatching null
                    val chance = (fields["rain_chance"] as? FieldValue.Num)?.value ?: 0.0
                    com.personalos.app.core.model.WeatherDay(
                        date = row.source.substringAfterLast(":fc:"),
                        maxC = kotlin.math.round(max).toInt(),
                        minC = kotlin.math.round(min).toInt(),
                        rainChance = kotlin.math.round(chance).toInt(),
                        weatherCode = (fields["weather_code"] as? FieldValue.Num)?.value?.toInt(),
                    )
                }.getOrNull()
            }.sortedBy { it.date }
    }

    /**
     * Tracked FX pairs in source order: each identity's latest observation as
     * an [FxRate]. Missing observations are absent, never zero.
     */
    suspend fun fxRates(identitiesInOrder: List<Pair<String, String>>): List<com.personalos.app.core.model.FxRate> {
        if (identitiesInOrder.isEmpty()) return emptyList()
        val views = latestMany(identitiesInOrder.map { it.second }).associateBy { it.source }
        return identitiesInOrder.mapNotNull { (label, identity) ->
            val view = views[identity] ?: return@mapNotNull null
            val rate = (view.fields["rate"] as? FieldValue.Num)?.value ?: return@mapNotNull null
            com.personalos.app.core.model.FxRate(
                id =
                    com.personalos.app.core.sources.SourceKeys
                        .slug(label),
                pair = label.uppercase(),
                rate = rate,
                note = "store",
            )
        }
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
