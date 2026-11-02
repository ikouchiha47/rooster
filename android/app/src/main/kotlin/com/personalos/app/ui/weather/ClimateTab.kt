package com.personalos.app.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.climate.ClimateMetric
import com.personalos.app.core.climate.ClimateMetrics
import com.personalos.app.core.climate.ClimatePoint
import com.personalos.app.core.climate.ClimateSeries
import com.personalos.app.core.climate.ensoState
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
    onOpen: (String) -> Unit = {},
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
                ClimateCard(metric = metric, series = series[metric.id], onOpen = { onOpen(metric.id) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ClimateCard(
    metric: ClimateMetric,
    series: ClimateSeries?,
    onOpen: () -> Unit,
) {
    val latest = series?.latest
    val color =
        when (metric.id) {
            ClimateMetrics.GLOBAL_TEMP.id -> CategoryColors.Vermilion
            ClimateMetrics.ONI.id -> CategoryColors.Mustard
            ClimateMetrics.CO2.id -> CategoryColors.Teal
            ClimateMetrics.ARCTIC_ICE.id, ClimateMetrics.ANTARCTIC_ICE.id -> CategoryColors.Indigo
            else -> CategoryColors.Cyan
        }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = metric.label, style = RadarType.serifTitle, color = RadarColors.ink)
            Spacer(Modifier.weight(1f))
            Text(
                text =
                    if (latest != null) {
                        if (metric.id == ClimateMetrics.ONI.id) {
                            "ONI %+.2f °C".format(latest.value)
                        } else {
                            "%.2f %s".format(latest.value, metric.unit)
                        }
                    } else {
                        "—"
                    },
                style = RadarType.monoStat,
                color = if (latest != null) RadarColors.ink else RadarColors.ink3,
            )
        }
        if (metric.description.isNotBlank()) {
            Text(
                text = metric.description,
                style = RadarType.microPlain,
                color = RadarColors.ink2,
                maxLines = 2,
            )
        }
        if (metric.id == ClimateMetrics.ONI.id && latest != null) {
            Text(
                text = ensoState(latest.value).uppercase(),
                style = RadarType.label,
                color = CategoryColors.Mustard,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "VS ${metric.baseline}", style = RadarType.labelMicro, color = RadarColors.ink3)
            Spacer(Modifier.weight(1f))
            Text(
                text = series?.sourceId?.uppercase() ?: "NOT LOADED",
                style = RadarType.labelMicro,
                color = if (series != null) CategoryColors.Teal else RadarColors.ink3,
            )
        }
        if (series != null && series.points.size > 1) {
            ClimateSparkline(points = series.points, color = color)
        }
        if (latest != null) {
            val anomaly = latest.anomaly
            if (anomaly != null && anomaly != latest.value) {
                Text(
                    text = "${"%+.2f".format(anomaly)} ${metric.unit} vs ${metric.baseline}",
                    style = RadarType.microPlain,
                    color = RadarColors.ink2,
                )
            }
            Text(
                text = "LAST UPDATED ${UPDATED_FMT.format(Date(series.fetchedAt)).uppercase()}",
                style = RadarType.labelMicro,
                color = RadarColors.ink3,
            )
        }
        SoftRule()
    }
}

@Composable
private fun ClimateSparkline(
    points: List<ClimatePoint>,
    color: androidx.compose.ui.graphics.Color,
) {
    Canvas(Modifier.fillMaxWidth().height(34.dp).padding(vertical = 4.dp)) {
        val values = points.map { it.value }
        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).coerceAtLeast(1)
            val y = size.height - ((value - min) / span * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(
            color = color.copy(alpha = 0.22f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f,
        )
        drawPath(path, color = color, style = Stroke(width = 1.6f))
    }
}
