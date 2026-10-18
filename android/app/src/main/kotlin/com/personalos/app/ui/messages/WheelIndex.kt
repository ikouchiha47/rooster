package com.personalos.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * A rotary drum A–Z index. The rotation lives in a [MutableFloatState] read *here*
 * only, so dragging recomposes this wheel and not the whole Contacts screen.
 * Drag to rotate and release to jump; hold still for [HOLD_MS] and the front
 * letter is handed to [onHold] (typing into the search) without focus.
 */
@Composable
fun WheelIndex(
    letters: List<String>,
    rotation: MutableFloatState,
    onJump: (String) -> Unit,
    onHold: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.size < 2) return
    val density = LocalDensity.current
    val step = 360f / letters.size
    val itemPx = with(density) { 42.dp.toPx() }
    val radius = (itemPx / 2f) / tan(((step / 2f) * PI / 180f).toDouble()).toFloat()
    val slop = with(density) { 8.dp.toPx() }
    val maxRotation = (letters.size - 1) * step
    val degreesPerPx = 180f / (PI.toFloat() * radius)

    fun indexOf(value: Float) = (value / step).roundToInt().coerceIn(0, letters.size - 1)

    fun letterOf(value: Float) = letters[indexOf(value)]

    Box(
        modifier =
            modifier
                .size(88.dp, 300.dp)
                .clipToBounds()
                .background(RadarColors.paper2)
                .pointerInput(letters) {
                    coroutineScope {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startRotation = rotation.floatValue
                            var moved = false
                            var committed = false
                            val hold =
                                launch {
                                    delay(HOLD_MS)
                                    if (!moved) {
                                        committed = true
                                        onHold(letterOf(rotation.floatValue))
                                    }
                                }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) break
                                val dy = change.position.y - down.position.y
                                if (abs(dy) > slop) {
                                    moved = true
                                    hold.cancel()
                                    rotation.floatValue = (startRotation + dy * degreesPerPx).coerceIn(0f, maxRotation)
                                }
                                change.consume()
                            }
                            hold.cancel()
                            if (!committed) {
                                val snapped = indexOf(rotation.floatValue).toFloat() * step
                                rotation.floatValue = snapped
                                onJump(letterOf(snapped))
                            }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        val rotationValue = rotation.floatValue
        letters.forEachIndexed { index, letter ->
            val angle = rotationValue - index * step
            val normalised = ((angle + 180f) % 360f + 360f) % 360f - 180f
            val radians = (angle * PI / 180f).toFloat()
            val alpha = cos(radians).coerceIn(0f, 1f)
            val front = abs(normalised) < step / 2f
            Text(
                text = letter,
                style = if (front) RadarType.serifH3 else RadarType.serifTitle,
                color = if (front) RadarColors.ink else RadarColors.ink3,
                modifier =
                    Modifier.graphicsLayer {
                        rotationX = angle
                        translationY = radius * sin(radians)
                        cameraDistance = radius * 4f
                        this.alpha = alpha
                    },
            )
        }
    }
}

private const val HOLD_MS = 450L
