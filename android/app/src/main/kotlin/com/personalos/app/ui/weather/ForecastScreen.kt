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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.model.WeatherDay
import com.personalos.app.data.AppDatabase
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
 * Reads the store (`weather:<slug>:fc:<date>` rows), so it opens instantly and
 * works offline. SYNC runs the gauge adapters, which rewrite the week's rows.
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
    val context = LocalContext.current
    val database = remember { AppDatabase.getInstance(context) }
    val maxId by database.eventDao().observeMaxId().collectAsStateWithLifecycle(initialValue = null)

    var reload by remember { mutableStateOf(0) }
    val lastSync by container.feeds.lastSyncAt.collectAsStateWithLifecycle(initialValue = 0L)
    // A configured place reads its week from the store. Anything else (the
    // present location) is not a source, so it reads the provider cache.
    var configured by remember { mutableStateOf(true) }
    LaunchedEffect(place) {
        configured =
            runCatching { database.sourceDao().enabled() }
                .getOrDefault(emptyList())
                .any { row ->
                    row.kind == "weather" &&
                        runCatching {
                            (
                                com.personalos.app.core.sources.SourceSpecs
                                    .parse(row.kind, row.specJson) as com.personalos.app.core.sources.WeatherSpec
                            ).place == place
                        }.getOrDefault(false)
                }
    }
    val providerDays by
        remember(place, reload, configured) {
            if (configured) {
                kotlinx.coroutines.flow.flowOf(emptyList<WeatherDay>())
            } else {
                container.weather.forecast(place)
            }
        }.collectAsStateWithLifecycle(initialValue = emptyList())
    var storeDays by remember { mutableStateOf<List<WeatherDay>>(emptyList()) }
    LaunchedEffect(place, maxId, reload, configured) {
        storeDays =
            if (!configured) {
                emptyList()
            } else {
                container.observationRepository.forecastWeek(
                    com.personalos.app.core.sources.SourceKeys
                        .slug(place),
                )
            }
    }
    val days = if (configured) storeDays else providerDays

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
            subtitle = {
                Text(
                    text = syncLabel(lastSync),
                    style = RadarType.labelMicro,
                    color = RadarColors.paper4,
                )
            },
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
                                        runCatching { container.feeds.refresh(force = true) }
                                        reload++
                                        container.sync.step("done")
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

private val SYNC_FMT = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())

/** When the store was last filled: a date, or the honest absence of one. */
private fun syncLabel(lastSync: Long): String =
    if (lastSync <= 0L) {
        "NOT SYNCED YET"
    } else {
        "SYNCED ${SYNC_FMT.format(java.util.Date(lastSync)).uppercase()}"
    }
