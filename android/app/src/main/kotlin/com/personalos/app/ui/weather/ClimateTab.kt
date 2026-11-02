package com.personalos.app.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.climate.ClimateMetric
import com.personalos.app.core.climate.ClimateMetrics
import com.personalos.app.core.climate.ClimateSeries
import com.personalos.app.data.ClimateRepository
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FEATURED =
    listOf(
        ClimateMetrics.GLOBAL_TEMP,
        ClimateMetrics.ONI,
        ClimateMetrics.CO2,
        ClimateMetrics.ARCTIC_ICE,
        ClimateMetrics.SEA_LEVEL,
    )

private val UPDATED_FMT = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)

/**
 * The slow climate signals. This is intentionally not local weather: every card
 * names its baseline, source and last update.
 */
@Composable
fun ClimateTab(
    repository: ClimateRepository,
    modifier: Modifier = Modifier,
) {
    val dashboard by repository.dashboard().collectAsStateWithLifecycle(initialValue = null)
    val series = dashboard?.series.orEmpty()
    LazyColumn(modifier.fillMaxSize()) {
        item {
            val updated = dashboard?.fetchedAt?.let { UPDATED_FMT.format(Date(it)) } ?: "not loaded"
            Row(
                modifier = Modifier.fillMaxWidth().background(RadarColors.paper3).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "GLOBAL CLIMATE", style = RadarType.label, color = RadarColors.ink)
                Spacer(Modifier.weight(1f))
                Text(text = "UPDATED $updated".uppercase(), style = RadarType.labelMicro, color = RadarColors.ink3)
            }
            SoftRule()
        }
        if (series.isEmpty()) {
            item {
                Text(
                    text = "No climate data yet. Add a climate source and sync.",
                    style = RadarType.body,
                    color = RadarColors.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        } else {
            items(FEATURED, key = { it.id }) { metric ->
                ClimateCard(metric = metric, series = series[metric.id])
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ClimateCard(
    metric: ClimateMetric,
    series: ClimateSeries?,
) {
    val latest = series?.latest
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = metric.label, style = RadarType.serifTitle, color = RadarColors.ink)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (latest != null) "%.2f %s".format(latest.value, metric.unit) else "—",
                style = RadarType.monoStat,
                color = if (latest != null) RadarColors.ink else RadarColors.ink3,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "BASE ${metric.baseline}", style = RadarType.labelMicro, color = RadarColors.ink3)
            Spacer(Modifier.weight(1f))
            Text(
                text = series?.sourceId?.uppercase() ?: "NOT LOADED",
                style = RadarType.labelMicro,
                color = CategoryColors.Teal,
            )
        }
        if (latest != null) {
            latest.anomaly?.let {
                Text(
                    text = "ANOMALY ${"%+.2f".format(it)} ${metric.unit}",
                    style = RadarType.microPlain,
                    color = RadarColors.ink2,
                )
            }
        }
        SoftRule()
    }
}
