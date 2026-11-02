package com.personalos.app.ui.hazards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.hazard.HazardSnapshot
import com.personalos.app.core.hazard.Quake
import com.personalos.app.core.map.MapCamera
import com.personalos.app.core.tile.ActionKind
import com.personalos.app.core.tile.TileConfig
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.HeaderMeta
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.MapLibreWorldMap
import com.personalos.app.ui.common.MapMarker
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import com.personalos.app.ui.tile.TileScaffold
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HAZARDS_TILE =
    TileConfig(
        id = "hazards",
        title = "Hazards",
        searchHint = "Search place or magnitude",
        actions = listOf(ActionKind.SYNC),
    )

private val TIME_FORMAT = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

/**
 * Hazards is its own service, not a tab inside Weather or Climate: an
 * earthquake is a discrete event with a location, not a forecast or a slow
 * global signal.
 *
 * USGS supplies the facts; this screen only maps them. The marker colour and
 * the row's severity band both come from [magnitudeColor], so the map and list
 * cannot disagree. Tapping a marker selects its row; tapping a row recentres
 * the map on that event.
 */
@Composable
fun HazardsScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val feed = remember(container) { container.hazards.observe() }
    val snapshot by
        feed.collectAsStateWithLifecycle(initialValue = container.hazards.snapshot.value)

    var camera by remember { mutableStateOf(MapCamera(18.0, 0.0, 0.4)) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var minMagnitude by remember { mutableStateOf<Double?>(null) }
    var visibleCount by remember { mutableIntStateOf(20) }

    // Ordering is a display decision, so it lives here rather than in the feed.
    val ordered = remember(snapshot.quakes) { snapshot.quakes.sortedByDescending { it.time } }
    LaunchedEffect(minMagnitude) { visibleCount = 20 }

    fun select(quake: Quake) {
        selectedId = quake.id
        camera = camera.copy(lat = quake.lat, lon = quake.lon, zoom = maxOf(camera.zoom, 4.0))
    }

    TileScaffold(
        config = HAZARDS_TILE,
        modifier = modifier,
        accent = CategoryColors.Rust,
        onBack = onBack,
        header = {
            HeaderMeta(
                primary = "${ordered.size} EVENTS",
                secondary = syncLabel(snapshot),
            )
        },
        onAction = { action ->
            if (action == ActionKind.SYNC) {
                scope.launch {
                    container.sync.run("hazards") {
                        container.sync.step("fetching USGS earthquakes")
                        container.hazards.refresh()
                        container.sync.step("done: ${container.hazards.snapshot.value.quakes.size} events")
                    }
                }
            }
        },
    ) { query ->
        val shown =
            remember(ordered, query, minMagnitude) {
                val needle = query.trim()
                ordered.filter { quake ->
                    (minMagnitude == null || (quake.magnitude ?: 0.0) >= minMagnitude!!) &&
                        (needle.isEmpty() || quake.matches(needle))
                }
            }
        val largest = remember(shown) { shown.sortedByDescending { it.magnitude ?: 0.0 }.take(4) }
        val visible = shown.take(visibleCount)
        val dayGroups =
            remember(visible) {
                visible
                    .groupBy { dayKey(it.time) }
                    .toList()
                    .sortedByDescending { it.first }
            }

        Column(Modifier.fillMaxSize()) {
            HazardFilterBar(
                minMagnitude = minMagnitude,
                onMinMagnitude = { minMagnitude = it },
            )
            MagnitudeLegend()
            MapLibreWorldMap(
                camera = camera,
                overlayTileUrl = null,
                markers =
                    shown.map {
                        MapMarker(
                            id = it.id,
                            label = it.place,
                            lat = it.lat,
                            lon = it.lon,
                            color = magnitudeColor(it.magnitude),
                        )
                    },
                onCameraChange = { camera = it },
                onMarkerClick = { id -> shown.firstOrNull { it.id == id }?.let(::select) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
            LargestCarousel(
                largest = largest,
                onSelect = ::select,
            )
            WidgetHeader(
                title = "Earthquakes",
                note = if (shown.size == ordered.size) "${ordered.size}" else "${shown.size}/${ordered.size}",
                glyph = Glyph.Storm,
            )

            snapshot.error?.let { message ->
                Text(
                    text =
                        if (snapshot.hasData) {
                            "Showing last good data ${Chars.MIDDLE_DOT} $message"
                        } else {
                            "USGS unavailable ${Chars.MIDDLE_DOT} $message"
                        },
                    style = RadarType.microPlain,
                    color = CategoryColors.Vermilion,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(RadarColors.paper3)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            if (shown.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = emptyLabel(snapshot, query),
                        style = RadarType.body,
                        color = RadarColors.ink3,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    dayGroups.forEach { (day, quakes) ->
                        stickyHeader(key = "day:$day") {
                            Text(
                                text = dayLabel(day),
                                style = RadarType.labelMicro,
                                color = RadarColors.ink2,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(RadarColors.paper3)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        items(quakes, key = { it.id }) { quake ->
                            QuakeRow(
                                quake = quake,
                                selected = quake.id == selectedId,
                                onSelect = { select(quake) },
                            )
                        }
                    }
                    if (visibleCount < shown.size) {
                        item(key = "more") {
                            FlatButton(
                                text = "Load ${minOf(20, shown.size - visibleCount)} more",
                                onClick = { visibleCount = minOf(shown.size, visibleCount + 20) },
                            )
                        }
                    }
                }
            }

            FooterStrip(
                "USGS all earthquakes, past day ${Chars.MIDDLE_DOT} " +
                    "${ordered.size} events ${Chars.MIDDLE_DOT} " +
                    (if (snapshot.error == null) "live" else "cached"),
            )
        }
    }
}

@Composable
private fun HazardFilterBar(
    minMagnitude: Double?,
    onMinMagnitude: (Double?) -> Unit,
) {
    val options = listOf<Pair<String, Double?>>("All" to null, "M2.5+" to 2.5, "M4.5+" to 4.5, "M5+" to 5.0)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, (label, value) ->
            if (index > 0) Spacer(Modifier.width(4.dp))
            FlatButton(text = label, onClick = { onMinMagnitude(value) }, solid = minMagnitude == value)
        }
    }
}

@Composable
private fun MagnitudeLegend() {
    val bands = listOf("<3", "3–3.9", "4–4.9", "5–5.9", "6+")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bands.forEachIndexed { index, label ->
            if (index > 0) Spacer(Modifier.width(10.dp))
            val band =
                when (index) {
                    0 -> 2.9
                    1 -> 3.9
                    2 -> 4.9
                    3 -> 5.9
                    else -> 6.0
                }
            Box(Modifier.width(7.dp).height(7.dp).background(magnitudeColor(band)))
            Spacer(Modifier.width(4.dp))
            Text(text = label, style = RadarType.labelMicro, color = RadarColors.ink3)
        }
    }
}

@Composable
private fun LargestCarousel(
    largest: List<Quake>,
    onSelect: (Quake) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        WidgetHeader(title = "Largest", note = "24H", glyph = Glyph.Storm)
        if (largest.isEmpty()) {
            Text(
                text = "No events at this filter.",
                style = RadarType.small,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(largest, key = { it.id }) { quake ->
                    LargestCard(quake = quake, onSelect = { onSelect(quake) })
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun LargestCard(
    quake: Quake,
    onSelect: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(190.dp)
                .background(RadarColors.paper2)
                .clickable(onClick = onSelect)
                .padding(8.dp),
    ) {
        Text(
            text = quake.magnitude?.let { String.format(Locale.US, "%.1f", it) } ?: Chars.EM_DASH,
            style = RadarType.monoH2,
            color = magnitudeColor(quake.magnitude),
        )
        Text(
            text = quake.place,
            style = RadarType.small,
            color = RadarColors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = timeLabel(quake.time), style = RadarType.microPlain, color = RadarColors.ink3, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(magnitudeColor(quake.magnitude)))
    }
}

@Composable
private fun QuakeRow(
    quake: Quake,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val color = magnitudeColor(quake.magnitude)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) RadarColors.paper3 else RadarColors.paper2)
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(34.dp).background(color))
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = quake.place,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${timeLabel(quake.time)} ${Chars.MIDDLE_DOT} ${depthLabel(quake.depth)}",
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = quake.magnitude?.let { String.format(Locale.US, "%.1f", it) } ?: Chars.EM_DASH,
                    style = RadarType.monoStat,
                    color = if (quake.magnitude == null) RadarColors.ink3 else RadarColors.ink,
                    maxLines = 1,
                )
                Text(text = "MAG", style = RadarType.micro, color = RadarColors.ink3, maxLines = 1)
            }
        }
        SoftRule()
    }
}

/** The one severity ramp, shared by markers and rows. */
private fun magnitudeColor(magnitude: Double?): Color =
    when {
        magnitude == null -> RadarColors.ink3
        magnitude >= 6.0 -> CategoryColors.Vermilion
        magnitude >= 5.0 -> CategoryColors.Rust
        magnitude >= 4.0 -> CategoryColors.Mustard
        magnitude >= 3.0 -> CategoryColors.Chartreuse
        else -> CategoryColors.Cyan
    }

private val DAY_KEY = SimpleDateFormat("yyyyMMdd", Locale.US)
private val DAY_LABEL = SimpleDateFormat("d MMM", Locale.US)

private fun dayKey(time: Long): String = if (time <= 0L) "unknown" else DAY_KEY.format(Date(time))

private fun dayLabel(key: String): String =
    when (key) {
        "unknown" -> "TIME UNKNOWN"
        dayKey(System.currentTimeMillis()) -> "TODAY"
        dayKey(System.currentTimeMillis() - 86_400_000L) -> "YESTERDAY"
        else -> runCatching { DAY_LABEL.format(DAY_KEY.parse(key)!!).uppercase(Locale.US) }.getOrDefault(key)
    }

private fun Quake.matches(needle: String): Boolean =
    place.contains(needle, ignoreCase = true) ||
        magnitude?.let { String.format(Locale.US, "%.1f", it).contains(needle) } == true

private fun timeLabel(time: Long): String =
    if (time <= 0L) {
        "time unknown"
    } else {
        TIME_FORMAT.format(Date(time)).uppercase()
    }

private fun depthLabel(depth: Double?): String = depth?.let { String.format(Locale.US, "%.0f km deep", it) } ?: "depth unknown"

private fun syncLabel(snapshot: HazardSnapshot): String =
    when {
        snapshot.fetchedAt != null -> "SYNCED ${timeLabel(snapshot.fetchedAt)}"
        snapshot.error != null -> "OFFLINE"
        else -> "WAITING FOR USGS"
    }

private fun emptyLabel(
    snapshot: HazardSnapshot,
    query: String,
): String =
    when {
        query.isNotBlank() -> "No events match \"$query\"."
        snapshot.error != null -> "No events yet. Tap Sync to retry USGS."
        snapshot.fetchedAt == null -> "Loading USGS earthquakes."
        else -> "No earthquakes in the past day."
    }
