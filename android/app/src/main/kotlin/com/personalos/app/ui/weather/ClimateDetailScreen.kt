package com.personalos.app.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.climate.ClimateMetrics
import com.personalos.app.core.climate.ClimatePoint
import com.personalos.app.core.climate.ensoState
import com.personalos.app.data.ClimateRepository
import com.personalos.app.ui.climate.nearestPointIndex
import com.personalos.app.ui.climate.pointPeriod
import com.personalos.app.ui.climate.togglePointSelection
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

private val SERIES_COLOR = CategoryColors.Cyan

/**
 * One climate series. Default view is the latest point; tapping/dragging selects
 * a real point, and tapping the selected point or LATEST clears back to latest.
 */
@Composable
fun ClimateDetailScreen(
    metricId: String,
    repository: ClimateRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dashboard by repository.dashboard().collectAsStateWithLifecycle(initialValue = null)
    val metric = ClimateMetrics.byId(metricId)
    val series = dashboard?.series?.get(metricId)
    val points = series?.points.orEmpty()
    var selected by remember(series) { mutableStateOf<Int?>(null) }
    val point = selected?.let { points.getOrNull(it) } ?: series?.latest

    Column(modifier.fillMaxSize().background(RadarColors.paper2)) {
        RadarHeader(
            title = metric?.label ?: "Climate",
            accent = CategoryColors.Cyan,
            onBack = onBack,
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (metric == null || series == null || point == null) {
                Text(
                    text = "This series is not loaded yet.",
                    style = RadarType.body,
                    color = RadarColors.ink3,
                )
                return@Column
            }
            Text(
                text = "%+.2f %s".format(point.value, metric.unit).trim(),
                style = RadarType.monoH2,
                color = RadarColors.ink,
            )
            Text(
                text = "${pointPeriod(point)} · ${if (selected == null) "LATEST" else "SELECTED"}",
                style = RadarType.labelMicro,
                color = if (selected == null) CategoryColors.Teal else CategoryColors.Rust,
            )
            if (metric.id == ClimateMetrics.ONI.id) {
                Text(
                    text = ensoState(point.value).uppercase(),
                    style = RadarType.label,
                    color = CategoryColors.Mustard,
                )
            }
            Text(
                text = metric.description,
                style = RadarType.small,
                color = RadarColors.ink2,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(8.dp))
            ClimateScrubChart(
                points = points,
                selected = selected,
                onSelect = { index -> selected = togglePointSelection(selected, index) },
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    FlatButton(text = "Latest", onClick = { selected = null }, solid = true)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = "VS ${metric.baseline}",
                    style = RadarType.labelMicro,
                    color = RadarColors.ink3,
                )
                Spacer(Modifier.weight(1f))
                Text(text = series.sourceId.uppercase(), style = RadarType.labelMicro, color = CategoryColors.Teal)
            }
            Text(
                text = "Tap a point to inspect it. Drag to scrub. Tap the selected dot or LATEST to clear.",
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                modifier = Modifier.padding(top = 6.dp),
            )
            SoftRule()
        }
    }
}

@Composable
private fun ClimateScrubChart(
    points: List<ClimatePoint>,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    if (points.size < 2) {
        Text(text = "Not enough points for a chart.", style = RadarType.microPlain, color = RadarColors.ink3)
        return
    }
    val values = points.map { it.value }
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(RadarColors.paper3)
            .pointerInput(points) {
                detectTapGestures { offset ->
                    nearestPointIndex(offset.x, size.width.toFloat(), points.size)?.let(onSelect)
                }
            }.pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        nearestPointIndex(offset.x, size.width.toFloat(), points.size)?.let(onSelect)
                    },
                    onHorizontalDrag = { change, _ ->
                        nearestPointIndex(change.position.x, size.width.toFloat(), points.size)?.let(onSelect)
                    },
                )
            },
    ) {
        val path = Path()
        points.forEachIndexed { index, p ->
            val x = size.width * index / (points.size - 1).coerceAtLeast(1)
            val y = size.height - ((p.value - min) / span * size.height * 0.8f).toFloat() - size.height * 0.1f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = SERIES_COLOR, style = Stroke(width = 2f))
        selected?.let { index ->
            val p = points.getOrNull(index) ?: return@let
            val x = size.width * index / (points.size - 1).coerceAtLeast(1)
            val y = size.height - ((p.value - min) / span * size.height * 0.8f).toFloat() - size.height * 0.1f
            drawLine(
                color = CategoryColors.Rust.copy(alpha = 0.7f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
            drawCircle(color = CategoryColors.Rust, radius = 4.5f, center = Offset(x, y))
        }
    }
}
