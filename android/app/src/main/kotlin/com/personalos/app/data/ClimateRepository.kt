package com.personalos.app.data

import com.personalos.app.core.climate.ClimateDashboard
import com.personalos.app.core.climate.ClimatePoint
import com.personalos.app.core.climate.ClimateSeries
import com.personalos.app.core.climate.ClimateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single owner of stored climate series. Sources fetch and normalise;
 * this class persists, groups and exposes them with freshness.
 */
class ClimateRepository(
    private val dao: ClimateDao,
    private val sources: List<ClimateSource>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun dashboard(): Flow<ClimateDashboard> =
        dao.observeAll().map { rows ->
            val series =
                rows.groupBy { it.metric to it.region }.mapValues { (_, points) ->
                    val first = points.first()
                    ClimateSeries(
                        metric = first.metric,
                        label = first.metric,
                        region = first.region,
                        unit = first.unit,
                        baseline = first.baseline,
                        points =
                            points.map {
                                ClimatePoint(it.periodStart, it.periodEnd, it.value, it.anomaly)
                            },
                        sourceId = first.sourceId,
                        sourceUrl = first.sourceUrl,
                        fetchedAt = points.maxOf { it.fetchedAt },
                    )
                }
            ClimateDashboard(
                series = series.values.associateBy { it.metric },
                fetchedAt = rows.maxOfOrNull { it.fetchedAt },
                stale = rows.isEmpty(),
            )
        }

    suspend fun refresh() {
        sources.forEach { source ->
            val series = runCatching { source.fetch() }.getOrNull() ?: return@forEach
            val rows =
                series.flatMap { series ->
                    series.points.map { point ->
                        ClimateSeriesEntity(
                            sourceId = series.sourceId,
                            metric = series.metric,
                            region = series.region,
                            periodStart = point.periodStart,
                            periodEnd = point.periodEnd,
                            value = point.value,
                            anomaly = point.anomaly,
                            unit = series.unit,
                            baseline = series.baseline,
                            sourceUrl = series.sourceUrl,
                            fetchedAt = series.fetchedAt,
                        )
                    }
                }
            if (rows.isNotEmpty()) dao.putAll(rows)
        }
    }
}
