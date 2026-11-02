package com.personalos.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp

/** Compact horizontal value picker: small track and knob, deliberately inset by callers. */
@Composable
fun CompactValueSlider(
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableIntStateOf(1) }

    fun update(x: Float) {
        if (max <= 0) return
        val fraction = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        onValueChange(((1f - fraction) * max).toInt().coerceIn(0, max))
    }
    Canvas(
        modifier
            .height(24.dp)
            .onSizeChanged { width = it.width }
            .pointerInput(max) {
                detectTapGestures { offset -> update(offset.x) }
            }.pointerInput(max) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    update(change.position.x)
                }
            },
    ) {
        val y = size.height / 2f
        drawLine(
            color = RadarColors.ruleSoft,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
        )
        val fraction = if (max <= 0) 1f else 1f - value.toFloat() / max
        val x = size.width * fraction
        drawCircle(color = RadarColors.ink, radius = 6.dp.toPx(), center = Offset(x, y))
        drawCircle(color = RadarColors.paper2, radius = 3.dp.toPx(), center = Offset(x, y))
    }
}
