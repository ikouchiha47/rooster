package com.personalos.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.personalos.app.ui.theme.RadarType

/** One cell of a [GlanceGrid]. */
data class GlanceCell(
    val label: String,
    val value: String,
    val suffix: String? = null,
    val valueColor: Color = RadarColors.ink,
)

/**
 * The bordered 2x2 "At a glance" card.
 *
 * Shared by any screen that needs a stat summary (Home, Radar), so the card
 * chrome, dividers and baseline alignment stay identical everywhere.
 */
@Composable
fun GlanceGrid(
    title: String,
    cells: List<GlanceCell>,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(RadarColors.paper2)
                .border(1.dp, RadarColors.ink),
    ) {
        WidgetHeader(title = title, note = note)

        cells.chunked(2).forEachIndexed { index, pair ->
            if (index > 0) SoftRule()
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                pair.forEachIndexed { i, cell ->
                    if (i > 0) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
                    }
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = cell.label.uppercase(),
                            style = RadarType.micro,
                            color = RadarColors.ink3,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(3.dp))
                        StatValue(
                            value = cell.value,
                            suffix = cell.suffix,
                            valueColor = cell.valueColor,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
