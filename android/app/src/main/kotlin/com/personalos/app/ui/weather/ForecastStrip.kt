package com.personalos.app.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personalos.app.core.Chars
import com.personalos.app.core.model.WeatherDay
import com.personalos.app.ui.common.GlyphIcon
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.forecastDayLabel
import com.personalos.app.ui.theme.RadarType

/** Height of one day column; fixed so the separators have something to fill. */
private val STRIP_HEIGHT = 104.dp

/** Width of one day column. Seven fit a wide phone; narrower screens scroll. */
private val COLUMN_WIDTH = 52.dp

/**
 * The seven-day forecast as a row of dense columns - `day / glyph / max-min /
 * rain%` - rather than one ruled row per day. Monochrome ink only, so it reads as
 * part of the newsprint page.
 *
 * Fixed-width columns inside a horizontal scroll: a full week fits a normal
 * phone, and the strip scrolls rather than compressing when it does not. The same
 * component is used on the Weather tile's card and on a place's forecast screen,
 * so the two can never drift apart.
 */
@Composable
fun ForecastStrip(
    days: List<WeatherDay>,
    modifier: Modifier = Modifier,
    columnWidth: Dp = COLUMN_WIDTH,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
    ) {
        days.forEachIndexed { index, day ->
            if (index > 0) {
                Box(Modifier.width(1.dp).height(STRIP_HEIGHT).background(RadarColors.ruleSoft))
            }
            ForecastColumn(day, columnWidth)
        }
    }
}

@Composable
private fun ForecastColumn(
    day: WeatherDay,
    width: Dp,
) {
    Column(
        modifier =
            Modifier
                .width(width)
                .height(STRIP_HEIGHT)
                .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = forecastDayLabel(day.date),
            style = RadarType.labelMicro,
            color = RadarColors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        GlyphIcon(day.condition().glyph, RadarColors.ink2, 22.dp)
        Spacer(Modifier.height(3.dp))
        Text(
            text = "${day.maxC}${Chars.DEGREE}",
            style = RadarType.monoStat,
            color = RadarColors.ink,
            maxLines = 1,
        )
        Text(
            text = "${day.minC}${Chars.DEGREE}",
            style = RadarType.monoSmall,
            color = RadarColors.ink3,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (day.rainChance > 0) "${day.rainChance}%" else Chars.EM_DASH,
            style = RadarType.monoSmall,
            color = RadarColors.ink2,
            maxLines = 1,
        )
    }
}
