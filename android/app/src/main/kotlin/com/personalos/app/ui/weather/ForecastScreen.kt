package com.personalos.app.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch

/**
 * Seven-day forecast for one place.
 *
 * Reads the provider's **cache only**, so it opens instantly and works offline -
 * there is no fetch on this path. [reload] re-subscribes the flow, which is what
 * the SYNC action means here.
 *
 * The header is the same band the Weather tile uses (same height, same accent),
 * so going from the tile into a place does not jump; the week below it is the
 * same dense strip as the tile's card.
 */
@Composable
fun ForecastScreen(
    place: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    var reload by remember { mutableStateOf(0) }
    val days by
        remember(place, reload) { container.weather.forecast(place) }
            .collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper2),
    ) {
        RadarHeader(
            title = place,
            accent = CategoryColors.Cyan,
            onBack = onBack,
            trailing = {
                Text(
                    text = "SYNC",
                    style = RadarType.labelMicro,
                    color = RadarColors.paper2,
                    modifier =
                        Modifier
                            .clickable {
                                scope.launch {
                                    container.sync.run("forecast") {
                                        container.sync.step("refreshing $place")
                                        reload++
                                    }
                                }
                            }.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            },
        )

        if (days.isEmpty()) {
            Text(
                text = "No forecast cached for this place yet.",
                style = RadarType.body,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(RadarColors.paper2)
                        .border(1.dp, RadarColors.ink),
                ) {
                    WidgetHeader(
                        title = "7-day forecast",
                        note = "${days.size} days",
                        glyph = Glyph.Sun,
                    )
                    ForecastStrip(days)
                }
            }
        }
    }
}
