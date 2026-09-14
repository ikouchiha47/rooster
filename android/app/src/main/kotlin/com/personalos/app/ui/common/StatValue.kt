package com.personalos.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalos.app.ui.theme.RadarType

/**
 * A big value followed by an optional small suffix, sharing **one text baseline**.
 *
 * This is the single place the "number + unit" pattern is styled, so every stat
 * in the app (At a glance cells, carousel pages, weather temperature) lines up
 * identically.
 *
 * Use this rather than hand-rolling a Row with `Alignment.Bottom`: bottom
 * alignment lines up the *boxes*, not the baselines, which reads as misaligned
 * whenever the two texts differ in size.
 */
@Composable
fun StatValue(
    value: String,
    suffix: String? = null,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = RadarType.monoStat,
    suffixStyle: TextStyle = RadarType.microPlain,
    valueColor: Color = RadarColors.ink,
    suffixColor: Color = RadarColors.ink3,
) {
    Row(modifier = modifier) {
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alignByBaseline(),
        )
        if (suffix != null) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = suffix,
                style = suffixStyle,
                color = suffixColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}
