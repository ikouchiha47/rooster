package com.personalos.app.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personalos.app.core.Chars
import com.personalos.app.core.model.WeatherDay
import com.personalos.app.ui.common.GlyphIcon
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.forecastDayLabel
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/** Height of one day column; fixed so the separators have something to fill. */
private val STRIP_HEIGHT = 104.dp

/** Width of one day column. Seven fit a wide phone; narrower screens scroll. */
private val COLUMN_WIDTH = 52.dp

/**
 * The seven-day forecast as a row of dense columns - `day / glyph / max-min /
 * rain%` - rather than one ruled row per day. Ink-first, so it reads as
 * part of the newsprint page; the only colour is a quiet condition wash
 * behind each column.
 *
 * Columns share the card width equally but never go below [columnWidth]: a
 * full week fills a normal phone edge to edge, and the strip only scrolls on
 * screens narrower than seven minimums. The same component is used on the
 * Weather tile's card and on a place's forecast screen, so the two can never
 * drift apart.
 *
 * Each column is centred in its own width, and carries a quiet condition tint
 * mixed from the existing palette onto paper (see [conditionTint]) so the strip
 * still sits calmly between paper cards. Only the thunder band goes dark, and it
 * switches to pale ink to stay readable.
 */
@Composable
fun ForecastStrip(
    days: List<WeatherDay>,
    modifier: Modifier = Modifier,
    columnWidth: Dp = COLUMN_WIDTH,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fits = maxWidth >= columnWidth * days.size
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())),
        ) {
            days.forEachIndexed { index, day ->
                if (index > 0) {
                    Box(Modifier.width(1.dp).height(STRIP_HEIGHT).background(RadarColors.ruleSoft))
                }
                if (fits) {
                    ForecastColumn(day, Modifier.weight(1f))
                } else {
                    ForecastColumn(day, Modifier.width(columnWidth))
                }
            }
        }
    }
}

/**
 * Background tint for one condition band.
 *
 * Every light band is an existing palette colour washed onto paper, so nothing
 * new enters the rainbow: straw from Mustard, greige from ink3, dusty sky from
 * Cyan. Only Storm stays solid - a mid-blue fails contrast both ways, while
 * solid Indigo carries pale paper text at ~7.9:1. Ink text on the light tints
 * stays above ~12:1.
 */
internal fun conditionTint(condition: WeatherCondition): Color =
    when (condition) {
        WeatherCondition.Clear -> lerp(RadarColors.paper2, CategoryColors.Mustard, 0.16f)
        WeatherCondition.PartlyCloudy -> lerp(RadarColors.paper2, CategoryColors.Mustard, 0.07f)
        WeatherCondition.Cloudy -> lerp(RadarColors.paper2, RadarColors.ink3, 0.14f)
        WeatherCondition.Rain -> lerp(RadarColors.paper2, CategoryColors.Cyan, 0.14f)
        WeatherCondition.Storm -> CategoryColors.Indigo
    }

/**
 * Background tint for one day, or null when there is nothing honest to tint
 * with: a rain chance outside 0..100 means no data, so the column stays exactly
 * as it always was - transparent, ink text, no invented weather.
 */
internal fun WeatherDay.stripTint(): Color? {
    if (rainChance !in 0..100) return null
    return conditionTint(condition())
}

/**
 * Whether the day's tint is dark enough to need pale ink. True only for a
 * thunder day with real data; every other tint keeps the usual ink scale.
 */
internal fun WeatherDay.stripOnDark(): Boolean = rainChance in 0..100 && condition() == WeatherCondition.Storm

@Composable
private fun ForecastColumn(
    day: WeatherDay,
    widthModifier: Modifier,
) {
    val onDark = day.stripOnDark()
    val primary = if (onDark) RadarColors.paper2 else RadarColors.ink
    val secondary = if (onDark) RadarColors.paper2 else RadarColors.ink2
    // paper4 is the palette's muted-on-ink text; ink3 is its on-paper equivalent.
    val tertiary = if (onDark) RadarColors.paper4 else RadarColors.ink3
    Column(
        modifier =
            Modifier
                .then(widthModifier)
                .height(STRIP_HEIGHT)
                .background(day.stripTint() ?: Color.Transparent)
                .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = forecastDayLabel(day.date),
            style = RadarType.labelMicro,
            color = primary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        GlyphIcon(day.condition().glyph, secondary, 22.dp)
        Spacer(Modifier.height(3.dp))
        Text(
            text = "${day.maxC}${Chars.DEGREE}",
            style = RadarType.monoStat,
            color = primary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "${day.minC}${Chars.DEGREE}",
            style = RadarType.monoSmall,
            color = tertiary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (day.rainChance > 0) "${day.rainChance}%" else Chars.EM_DASH,
            style = RadarType.monoSmall,
            color = secondary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
