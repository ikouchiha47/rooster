package com.personalos.app.ui.hazards

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.hazard.HazardArea
import com.personalos.app.core.hazard.HazardSnapshot
import com.personalos.app.core.hazard.HazardWindow
import com.personalos.app.core.hazard.Quake
import com.personalos.app.core.map.MapBounds
import com.personalos.app.core.map.MapCamera
import com.personalos.app.core.map.MapViewport
import com.personalos.app.core.map.SavedRegion
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
import com.personalos.app.ui.common.SaveRegionDialog
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import com.personalos.app.ui.tile.TileScaffold
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HazardFilters(
    val minMagnitude: Double? = null,
    val region: String? = null,
    val area: HazardArea? = null,
    val window: HazardWindow = HazardWindow.DAY,
)

private val HAZARDS_TILE =
    TileConfig(
        id = "hazards",
        title = "Hazards",
        searchHint = "Search place or magnitude",
        actions = listOf(ActionKind.SYNC, ActionKind.FILTER, ActionKind.FULLSCREEN),
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
    var visibleCount by remember { mutableIntStateOf(20) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)

    var filters by remember { mutableStateOf(HazardFilters()) }
    var draftMagnitude by remember { mutableStateOf<Double?>(null) }
    var draftRegion by remember { mutableStateOf<String?>(null) }
    var draftArea by remember { mutableStateOf<HazardArea?>(null) }
    var draftWindow by remember { mutableStateOf(HazardWindow.DAY) }
    var placingPin by remember { mutableStateOf(false) }
    var pendingPin by remember { mutableStateOf<MapCamera?>(null) }
    var showSaveRegion by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val savedRegions by
        container.regions.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    // Ordering is a display decision, so it lives here rather than in the feed.
    val ordered = remember(snapshot.quakes) { snapshot.quakes.sortedByDescending { it.time } }
    val regions = remember(ordered) { ordered.mapNotNull { regionOf(it.place) }.distinct().sorted() }
    DisposableEffect(fullscreen) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    LaunchedEffect(filters) { visibleCount = 20 }
    LaunchedEffect(filters.window) { container.hazards.refresh(filters.window) }

    fun openFilters() {
        draftMagnitude = filters.minMagnitude
        draftRegion = filters.region
        draftArea = filters.area
        draftWindow = filters.window
        scope.launch { drawer.open() }
    }

    fun select(quake: Quake) {
        selectedId = quake.id
        camera = camera.copy(lat = quake.lat, lon = quake.lon, zoom = maxOf(camera.zoom, 4.0))
    }

    fun dropPin(
        lat: Double,
        lon: Double,
    ) {
        if (!placingPin) return
        placingPin = false
        pendingPin = camera.copy(lat = lat, lon = lon)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                HazardFilterDrawer(
                    regions = regions,
                    savedRegions = savedRegions,
                    draftMagnitude = draftMagnitude,
                    draftRegion = draftRegion,
                    draftArea = draftArea,
                    draftWindow = draftWindow,
                    viewportAvailable = viewport != null,
                    onMagnitude = { draftMagnitude = it },
                    onRegion = { draftRegion = it },
                    onWindow = { draftWindow = it },
                    onCurrentMap = { viewport?.bounds?.let { draftArea = HazardArea.Bounds(it) } },
                    onSavedRegion = { draftArea = HazardArea.Saved(it) },
                    onClearArea = { draftArea = null },
                    onDropPin = {
                        scope.launch { drawer.close() }
                        placingPin = true
                    },
                    onReset = {
                        draftMagnitude = null
                        draftRegion = null
                        draftArea = null
                        draftWindow = HazardWindow.DAY
                    },
                    onApply = {
                        filters =
                            HazardFilters(
                                minMagnitude = draftMagnitude,
                                region = draftRegion,
                                area = draftArea,
                                window = draftWindow,
                            )
                        scope.launch { drawer.close() }
                    },
                )
            }
        },
    ) {
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
                when (action) {
                    ActionKind.FILTER -> openFilters()
                    ActionKind.FULLSCREEN -> fullscreen = true
                    ActionKind.SYNC ->
                        scope.launch {
                            container.sync.run("hazards") {
                                container.sync.step("fetching USGS earthquakes")
                                container.hazards.refresh(filters.window)
                                container.sync.step("done: ${container.hazards.snapshot.value.quakes.size} events")
                            }
                        }
                    else -> Unit
                }
            },
        ) { query ->
            val shown =
                remember(ordered, query, filters) {
                    val needle = query.trim()
                    ordered.filter { quake ->
                        (filters.minMagnitude == null || (quake.magnitude ?: 0.0) >= filters.minMagnitude!!) &&
                            (filters.region == null || regionOf(quake.place) == filters.region) &&
                            (filters.area == null || quake.inArea(filters.area!!)) &&
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
                    minMagnitude = filters.minMagnitude,
                    onMinMagnitude = { filters = filters.copy(minMagnitude = it) },
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
                    areaPin =
                        pendingPin?.let { MapMarker("pending", "Area pin", it.lat, it.lon) }
                            ?: when (val area = filters.area) {
                                is HazardArea.Pin -> MapMarker("area", "Area pin", area.camera.lat, area.camera.lon)
                                is HazardArea.Saved ->
                                    MapMarker("area", area.region.name, area.region.camera.lat, area.region.camera.lon)
                                else -> null
                            },
                    areaRadiusKm =
                        pendingPin?.radiusKm
                            ?: when (val area = filters.area) {
                                is HazardArea.Pin -> area.radiusKm
                                is HazardArea.Saved -> area.region.radiusKm
                                else -> null
                            },
                    onCameraChange = { camera = it },
                    onViewportChange = { viewport = it },
                    onMapTap = ::dropPin,
                    onMarkerClick = { id -> shown.firstOrNull { it.id == id }?.let(::select) },
                    mapAspectRatio = 2.4f,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (placingPin) {
                    Text(
                        text = "Tap the map to drop the area pin.",
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (pendingPin != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "Pin dropped", style = RadarType.microPlain, color = RadarColors.ink3)
                        Spacer(Modifier.width(8.dp))
                        FlatButton(
                            text = "Use area",
                            onClick = {
                                pendingPin?.let { pin ->
                                    filters = filters.copy(area = HazardArea.Pin(pin, pin.radiusKm))
                                }
                                pendingPin = null
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                        FlatButton(text = "Save region", onClick = { showSaveRegion = true })
                        Spacer(Modifier.width(4.dp))
                        FlatButton(text = "Cancel", onClick = { pendingPin = null })
                    }
                }
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
                    "USGS all earthquakes, ${filters.window.label} ${Chars.MIDDLE_DOT} " +
                        "${ordered.size} events ${Chars.MIDDLE_DOT} " +
                        (if (snapshot.error == null) "live" else "cached"),
                )
            }
        }
    }

    if (showSaveRegion) {
        val pin = pendingPin
        SaveRegionDialog(
            onDismiss = { showSaveRegion = false },
            radiusKm = pin?.radiusKm,
            onSave = { name ->
                if (pin != null) {
                    scope.launch {
                        val saved = container.regions.save(name = name, camera = pin)
                        filters = filters.copy(area = HazardArea.Saved(saved))
                        pendingPin = null
                    }
                }
            },
        )
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(RadarColors.paper2)) {
                MapLibreWorldMap(
                    camera = camera,
                    overlayTileUrl = null,
                    markers =
                        ordered
                            .filter { quake ->
                                (filters.minMagnitude == null || (quake.magnitude ?: 0.0) >= filters.minMagnitude!!) &&
                                    (filters.region == null || regionOf(quake.place) == filters.region) &&
                                    (filters.area == null || quake.inArea(filters.area!!))
                            }.map {
                                MapMarker(
                                    id = it.id,
                                    label = it.place,
                                    lat = it.lat,
                                    lon = it.lon,
                                    color = magnitudeColor(it.magnitude),
                                )
                            },
                    onCameraChange = { camera = it },
                    onMarkerClick = { id -> ordered.firstOrNull { it.id == id }?.let(::select) },
                    mapAspectRatio = null,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlatButton(text = "Exit map", onClick = { fullscreen = false })
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${ordered.size} events ${Chars.MIDDLE_DOT} ${filters.window.label}",
                        style = RadarType.labelMicro,
                        color = RadarColors.ink2,
                        modifier = Modifier.background(RadarColors.paper2).padding(6.dp),
                    )
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

@Composable
private fun HazardFilterDrawer(
    regions: List<String>,
    savedRegions: List<SavedRegion>,
    draftMagnitude: Double?,
    draftRegion: String?,
    draftArea: HazardArea?,
    draftWindow: HazardWindow,
    viewportAvailable: Boolean,
    onMagnitude: (Double?) -> Unit,
    onRegion: (String?) -> Unit,
    onWindow: (HazardWindow) -> Unit,
    onCurrentMap: () -> Unit,
    onSavedRegion: (SavedRegion) -> Unit,
    onClearArea: () -> Unit,
    onDropPin: () -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
) {
    var regionMenu by remember { mutableStateOf(false) }
    var savedMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Hazard filters",
            style = RadarType.serifTitle,
            color = RadarColors.ink,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        FilterLabel("REGION")
        Box(Modifier.padding(horizontal = 16.dp)) {
            FilterDropdown(text = draftRegion ?: "All regions", onClick = { regionMenu = true })
            DropdownMenu(expanded = regionMenu, onDismissRequest = { regionMenu = false }) {
                DropdownMenuItem(
                    text = { Text("All regions") },
                    onClick = {
                        regionMenu = false
                        onRegion(null)
                    },
                )
                regions.forEach { region ->
                    DropdownMenuItem(
                        text = { Text(region) },
                        onClick = {
                            regionMenu = false
                            onRegion(region)
                        },
                    )
                }
            }
        }
        FilterLabel("AREA", top = 12.dp)
        Text(
            text = draftArea.describe(),
            style = RadarType.microPlain,
            color = RadarColors.ink3,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            FlatButton(text = "Current map", onClick = onCurrentMap, enabled = viewportAvailable)
            Spacer(Modifier.width(4.dp))
            FlatButton(text = "Drop pin", onClick = onDropPin)
        }
        if (savedRegions.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                FilterDropdown(text = "Saved region", onClick = { savedMenu = true })
                DropdownMenu(expanded = savedMenu, onDismissRequest = { savedMenu = false }) {
                    savedRegions.forEach { region ->
                        DropdownMenuItem(
                            text = { Text(region.name) },
                            onClick = {
                                savedMenu = false
                                onSavedRegion(region)
                            },
                        )
                    }
                }
            }
        }
        if (draftArea != null) {
            FlatButton(
                text = "Clear area",
                onClick = onClearArea,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        FilterLabel("MIN MAGNITUDE", top = 12.dp)
        val mags = listOf<Pair<String, Double?>>("All" to null, "M2.5+" to 2.5, "M4.5+" to 4.5, "M5+" to 5.0)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            mags.forEach { (label, value) ->
                FlatButton(text = label, onClick = { onMagnitude(value) }, solid = draftMagnitude == value)
                Spacer(Modifier.width(4.dp))
            }
        }
        FilterLabel("WINDOW", top = 12.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            HazardWindow.entries.forEach { option ->
                FlatButton(text = option.label, onClick = { onWindow(option) }, solid = draftWindow == option)
                Spacer(Modifier.width(4.dp))
            }
        }
        Text(
            text = "USGS updates the 24H feed continuously. 7D and 30D are historical windows.",
            style = RadarType.microPlain,
            color = RadarColors.ink3,
            modifier = Modifier.padding(16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            FlatButton(text = "Reset", onClick = onReset)
            Spacer(Modifier.width(8.dp))
            FlatButton(text = "Apply", onClick = onApply)
        }
    }
}

@Composable
private fun FilterLabel(
    text: String,
    top: Dp = 4.dp,
) {
    Text(
        text = text,
        style = RadarType.labelMicro,
        color = RadarColors.ink3,
        modifier = Modifier.padding(start = 16.dp, top = top, bottom = 4.dp),
    )
}

@Composable
private fun FilterDropdown(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .border(1.dp, RadarColors.ruleSoft)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = RadarType.body, color = RadarColors.ink, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = RadarColors.ink3,
        )
    }
}

private fun HazardArea?.describe(): String =
    when (this) {
        null -> "No area filter."
        is HazardArea.Bounds -> "Current map bounds."
        is HazardArea.Pin -> "Dropped pin, radius ~${radiusKm.toInt()} km."
        is HazardArea.Saved -> "Saved region ${region.name}, radius ~${region.radiusKm.toInt()} km."
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
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        WidgetHeader(title = "Largest", note = "24H", glyph = Glyph.Storm)
        Spacer(Modifier.height(8.dp))
        if (largest.isEmpty()) {
            Text(
                text = "No events at this filter.",
                style = RadarType.small,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else {
            val pager = rememberPagerState(pageCount = { largest.size })
            LaunchedEffect(largest) {
                if (pager.currentPage > largest.lastIndex) pager.scrollToPage(0)
            }
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 8.dp),
                pageSpacing = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                LargestCard(quake = largest[page], onSelect = { onSelect(largest[page]) })
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    largest.indices.forEach { index ->
                        Box(
                            Modifier
                                .padding(horizontal = 2.dp)
                                .width(if (index == pager.currentPage) 14.dp else 6.dp)
                                .height(3.dp)
                                .background(if (index == pager.currentPage) RadarColors.ink else RadarColors.ruleSoft),
                        )
                    }
                }
                Text(
                    text = "${pager.currentPage + 1} / ${largest.size}",
                    style = RadarType.labelMicro,
                    color = RadarColors.ink3,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                )
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
                .fillMaxWidth()
                .height(96.dp)
                .background(magnitudePastel(quake.magnitude))
                .border(1.dp, RadarColors.ruleSoft)
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
        Text(
            text = "${timeLabel(quake.time)} ${Chars.MIDDLE_DOT} ${depthLabel(quake.depth)}",
            style = RadarType.microPlain,
            color = RadarColors.ink2,
            maxLines = 1,
        )
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
private fun magnitudePastel(magnitude: Double?): Color =
    when {
        magnitude == null -> RadarColors.paper3
        magnitude >= 6.0 -> Color(0xFFF3D3CD)
        magnitude >= 5.0 -> Color(0xFFF1DDD0)
        magnitude >= 4.0 -> Color(0xFFF7EBC8)
        magnitude >= 3.0 -> Color(0xFFE8F0D3)
        else -> Color(0xFFDCECEF)
    }

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

private fun Quake.inArea(area: HazardArea): Boolean =
    when (area) {
        is HazardArea.Bounds -> inBounds(area.bounds)
        is HazardArea.Pin -> distanceKm(lat, lon, area.camera.lat, area.camera.lon) <= area.radiusKm
        is HazardArea.Saved ->
            distanceKm(lat, lon, area.region.camera.lat, area.region.camera.lon) <= area.region.radiusKm
    }

private fun distanceKm(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a =
        kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return 6371.0 * 2.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

private fun regionOf(place: String): String? =
    place.substringAfterLast(',', "").trim().ifBlank {
        place.substringAfterLast(" of ", "").trim().takeIf { it.isNotBlank() }
    }

private fun Quake.inBounds(bounds: MapBounds): Boolean {
    if (lat < bounds.south || lat > bounds.north) return false
    return if (bounds.west <= bounds.east) {
        lon in bounds.west..bounds.east
    } else {
        lon >= bounds.west || lon <= bounds.east
    }
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
