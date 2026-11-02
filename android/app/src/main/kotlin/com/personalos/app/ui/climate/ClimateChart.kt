package com.personalos.app.ui.climate

import com.personalos.app.core.climate.ClimatePoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Index of the nearest real point to a horizontal chart position. */
fun nearestPointIndex(
    x: Float,
    width: Float,
    count: Int,
): Int? {
    if (count <= 0 || width <= 0f) return null
    if (count == 1) return 0
    val ratio = (x / width).coerceIn(0f, 1f)
    return (ratio * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

/** A tap on the selected point clears it; any other point becomes selected. */
fun togglePointSelection(
    current: Int?,
    tapped: Int,
): Int? = if (current == tapped) null else tapped

private val PERIOD_FMT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

fun pointPeriod(point: ClimatePoint): String =
    Instant
        .ofEpochMilli(point.periodStart)
        .atZone(ZoneOffset.UTC)
        .format(PERIOD_FMT)
        .uppercase(Locale.ENGLISH)
