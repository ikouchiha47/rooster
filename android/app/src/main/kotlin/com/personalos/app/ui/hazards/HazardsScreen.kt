package com.personalos.app.ui.hazards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    // Ordering is a display decision, so it lives here rather than in the feed.
    val ordered = remember(snapshot.quakes) { snapshot.quakes.sortedByDescending { it.time } }

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
            remember(ordered, query) {
                val needle = query.trim()
                if (needle.isEmpty()) {
                    ordered
                } else {
                    ordered.filter { it.matches(needle) }
                }
            }

        Column(Modifier.fillMaxSize()) {
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
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
                    items(shown, key = { it.id }) { quake ->
                        QuakeRow(
                            quake = quake,
                            selected = quake.id == selectedId,
                            onSelect = { select(quake) },
                        )
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
