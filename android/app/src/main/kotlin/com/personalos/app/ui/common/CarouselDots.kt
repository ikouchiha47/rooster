package com.personalos.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Page indicator dots, as a real carousel has: one dot per page, the active one
 * filled and slightly larger, **centred** in the available width. Dots are
 * tappable when [onDotClick] is supplied.
 */
@Composable
fun CarouselDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
    onDotClick: ((Int) -> Unit)? = null,
) {
    if (count <= 1) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier =
                    Modifier
                        .size(if (active) 5.dp else 4.dp)
                        .background(
                            if (active) RadarColors.ink else RadarColors.ruleSoft,
                            CircleShape,
                        ).then(
                            if (onDotClick != null) {
                                Modifier.clickable { onDotClick(index) }
                            } else {
                                Modifier
                            },
                        ),
            )
        }
    }
}
