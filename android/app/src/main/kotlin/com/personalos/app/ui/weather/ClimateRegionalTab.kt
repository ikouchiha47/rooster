package com.personalos.app.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.map.MapCamera
import com.personalos.app.core.map.MapViewport
import com.personalos.app.core.map.RegionConsumers
import com.personalos.app.core.map.SavedRegion
import com.personalos.app.data.remote.climate.ClimateColormap
import com.personalos.app.data.remote.climate.fetchClimateColormap
import com.personalos.app.data.remote.climate.fetchRangeBitmap
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.MapHighlight
import com.personalos.app.ui.common.MapLibreWorldMap
import com.personalos.app.ui.common.MapMarker
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RegionControls
import com.personalos.app.ui.common.RemoteBitmapImage
import com.personalos.app.ui.common.SaveRegionDialog
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

private val MONTH_FMT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

private data class RegionalLayer(
    val id: String,
    val label: String,
    val gibsLayer: String,
    val start: YearMonth?,
    val latest: YearMonth?,
    val legendUrl: String?,
    val legendText: String? = null,
    val description: String,
    val wms: Boolean = false,
)

private val LAYERS =
    listOf(
        RegionalLayer(
            id = "temperature",
            label = "Temperature",
            gibsLayer = "MERRA2_2m_Air_Temperature_Monthly",
            start = YearMonth.of(1980, 1),
            latest = YearMonth.of(2026, 8),
            legendUrl = "https://gibs.earthdata.nasa.gov/legends/MERRA2_2m_Air_Temperature_Monthly_H.png",
            description = "Monthly mean air temperature. Not an anomaly.",
        ),
        RegionalLayer(
            id = "rain",
            label = "Rain",
            gibsLayer = "GLDAS_Surface_Total_Precipitation_Rate_Monthly",
            start = YearMonth.of(2000, 1),
            latest = YearMonth.of(2026, 6),
            legendUrl = "https://gibs.earthdata.nasa.gov/legends/GLDAS_Surface_Total_Precipitation_Rate_Monthly_H.png",
            description = "Monthly total precipitation rate.",
        ),
        RegionalLayer(
            id = "ice",
            label = "Sea ice",
            gibsLayer = "AMSRU2_Sea_Ice_Concentration_12km",
            start = YearMonth.of(2012, 7),
            latest = YearMonth.of(2025, 9),
            legendUrl = null,
            legendText = "White / grey = ice; blue = open water.",
            description = "Polar sea-ice concentration, Arctic or Antarctic. Not sea level.",
        ),
        RegionalLayer(
            id = "fires",
            label = "Fires",
            gibsLayer = "VIIRS_SNPP_Thermal_Anomalies_375m_All",
            start = null,
            latest = null,
            legendUrl = null,
            legendText = "Orange dots = thermal anomalies.",
            description = "Latest thermal anomalies; not a monthly series.",
            wms = true,
        ),
    )

private fun gibsTile(
    layer: RegionalLayer,
    month: YearMonth,
): String =
    "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/${layer.gibsLayer}/" +
        "default/${month.atDay(1)}/GoogleMapsCompatible_Level6/{z}/{y}/{x}.png"

private fun gibsFireWms(layer: RegionalLayer): String =
    "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi" +
        "?SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1&SRS=EPSG:3857" +
        "&BBOX={bbox-epsg-3857}&WIDTH=256&HEIGHT=256" +
        "&LAYERS=${layer.gibsLayer}&FORMAT=image/png&TRANSPARENT=TRUE" +
        "&TIME=${LocalDate.now().minusDays(1)}"

@Composable
fun ClimateRegionalTab(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val regions by container.regions.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val climateDefault by
        container.regions
            .observeDefault(RegionConsumers.CLIMATE)
            .collectAsStateWithLifecycle(initialValue = null)
    val homeDefault by
        container.regions
            .observeDefault(RegionConsumers.HOME)
            .collectAsStateWithLifecycle(initialValue = null)

    var layerIndex by remember { mutableIntStateOf(0) }
    var monthOffset by remember { mutableIntStateOf(0) }
    var camera by remember { mutableStateOf(MapCamera(20.0, 0.0, 0.1)) }
    var selectedRegionId by remember { mutableStateOf<String?>(null) }
    var initialised by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var locateNote by remember { mutableStateOf<String?>(null) }
    var iceHemisphere by remember { mutableStateOf("arctic") }
    var placingPin by remember { mutableStateOf(false) }
    var pendingPin by remember { mutableStateOf<MapMarker?>(null) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var colormap by remember { mutableStateOf<ClimateColormap?>(null) }
    var rangeLow by remember { mutableStateOf(0f) }
    var rangeHigh by remember { mutableStateOf(1f) }
    var highlight by remember { mutableStateOf<MapHighlight?>(null) }

    val selectedRegion = regions.firstOrNull { it.id == selectedRegionId }
    val areaPin =
        pendingPin
            ?: selectedRegion?.let {
                MapMarker(it.id, it.name, it.camera.lat, it.camera.lon)
            }
    val areaRadiusKm = if (pendingPin != null) camera.radiusKm else selectedRegion?.radiusKm
    val layer = LAYERS[layerIndex]
    val maxOffset =
        if (layer.start != null && layer.latest != null) {
            ChronoUnit.MONTHS.between(layer.start, layer.latest).toInt()
        } else {
            0
        }
    val month = layer.latest?.minusMonths(monthOffset.toLong())
    val overlayUrl =
        remember(layer, month) {
            when {
                layer.wms -> gibsFireWms(layer)
                month != null -> gibsTile(layer, month)
                else -> null
            }
        }

    val supportsRange = layer.id == "temperature" || layer.id == "rain"
    val lowIndex = colormap?.colors?.lastIndex?.let { (rangeLow * it).toInt().coerceIn(0, it) } ?: 0
    val highIndex = colormap?.colors?.lastIndex?.let { (rangeHigh * it).toInt().coerceIn(0, it) } ?: 0
    val rangeActive = supportsRange && colormap != null && (lowIndex > 0 || highIndex < (colormap?.colors?.lastIndex ?: 0))

    LaunchedEffect(layer.id, supportsRange) {
        colormap = null
        rangeLow = 0f
        rangeHigh = 1f
        highlight = null
        if (supportsRange) {
            colormap = withContext(Dispatchers.IO) { fetchClimateColormap(layer.gibsLayer) }
        }
    }

    LaunchedEffect(viewport, layer.id, month, lowIndex, highIndex, rangeActive, colormap) {
        if (!rangeActive || month == null || colormap == null) {
            highlight = null
            return@LaunchedEffect
        }
        val current = viewport ?: return@LaunchedEffect
        highlight =
            withContext(Dispatchers.IO) {
                fetchRangeBitmap(
                    layer = layer.gibsLayer,
                    date = month.atDay(1).toString(),
                    bounds = current.bounds,
                    width = current.width,
                    height = current.height,
                    colormap = colormap!!,
                    lowIndex = lowIndex,
                    highIndex = highIndex,
                )?.let { MapHighlight(current.bounds, it) }
            }
    }

    LaunchedEffect(climateDefault, homeDefault, regions) {
        if (initialised) return@LaunchedEffect
        val start = climateDefault ?: homeDefault ?: regions.firstOrNull()
        if (start != null) {
            camera = start.camera
            selectedRegionId = start.id
            initialised = true
        } else if (regions.isNotEmpty() || climateDefault != null || homeDefault != null) {
            initialised = true
        }
    }

    fun polarCamera(hemisphere: String): MapCamera = MapCamera(lat = if (hemisphere == "arctic") 78.0 else -78.0, lon = 0.0, zoom = 2.6)

    fun selectLayer(index: Int) {
        layerIndex = index
        monthOffset = 0
        pendingPin = null
        placingPin = false
        if (LAYERS[index].id == "ice") {
            camera = polarCamera(iceHemisphere)
            selectedRegionId = null
        }
    }

    fun selectRegion(region: SavedRegion) {
        selectedRegionId = region.id
        camera = region.camera
        pendingPin = null
        placingPin = false
    }

    fun clearSelection() {
        selectedRegionId = null
        pendingPin = null
        placingPin = false
        camera = climateDefault?.camera ?: homeDefault?.camera ?: MapCamera(20.0, 0.0, 0.1)
    }

    fun dropPin(
        lat: Double,
        lon: Double,
    ) {
        if (!placingPin) return
        pendingPin = MapMarker("pending", "New pin", lat, lon)
        placingPin = false
    }

    fun locate() {
        if (locating) return
        locating = true
        locateNote = null
        scope.launch {
            val snapshot = runCatching { container.presentLocation.observe().firstOrNull() }.getOrNull()
            val lat = snapshot?.lat
            val lon = snapshot?.lon
            if (lat != null && lon != null) {
                camera = camera.copy(lat = lat, lon = lon, zoom = max(camera.zoom, 4.0))
                selectedRegionId = null
            } else {
                locateNote = "Location unavailable. Coarse permission is required."
            }
            locating = false
        }
    }

    Column(modifier.fillMaxSize().background(RadarColors.paper2)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LAYERS.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.width(4.dp))
                FlatButton(
                    text = item.label,
                    onClick = { selectLayer(index) },
                    solid = index == layerIndex,
                )
            }
        }
        if (layer.id == "ice") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlatButton(
                    text = "Arctic",
                    onClick = {
                        iceHemisphere = "arctic"
                        camera = polarCamera("arctic")
                        selectedRegionId = null
                    },
                    solid = iceHemisphere == "arctic",
                )
                Spacer(Modifier.width(4.dp))
                FlatButton(
                    text = "Antarctic",
                    onClick = {
                        iceHemisphere = "antarctic"
                        camera = polarCamera("antarctic")
                        selectedRegionId = null
                    },
                    solid = iceHemisphere == "antarctic",
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "POLAR ONLY", style = RadarType.labelMicro, color = RadarColors.ink3)
            }
        }
        SoftRule()

        RegionControls(
            regions = regions,
            defaultRegion = climateDefault ?: homeDefault,
            selectedId = selectedRegionId,
            onSelect = ::selectRegion,
            onSaveView = {
                placingPin = true
                selectedRegionId = null
            },
            onLocate = ::locate,
            onClear = ::clearSelection,
            locating = locating,
        )
        if (placingPin) {
            Text(
                text = "Tap the map to drop the pin.",
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
                FlatButton(text = "Save pin", onClick = { showSave = true })
                Spacer(Modifier.width(4.dp))
                FlatButton(text = "Cancel", onClick = { pendingPin = null })
            }
        }
        SoftRule()

        if (month != null && layer.start != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = month.format(MONTH_FMT).uppercase(Locale.ENGLISH),
                    style = RadarType.label,
                    color = RadarColors.ink,
                    modifier = Modifier.width(78.dp),
                )
                MonthSlider(
                    value = monthOffset,
                    max = maxOffset,
                    onValueChange = { monthOffset = it },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Text(
                text = "LATEST AVAILABLE",
                style = RadarType.labelMicro,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        if (supportsRange && colormap != null) {
            RangeLegend(
                colormap = colormap!!,
                low = rangeLow,
                high = rangeHigh,
                onLow = { rangeLow = it },
                onHigh = { rangeHigh = it },
                onClear = {
                    rangeLow = 0f
                    rangeHigh = 1f
                },
            )
        }

        MapLibreWorldMap(
            camera = camera,
            overlayTileUrl = if (rangeActive) null else overlayUrl,
            overlayMaxZoom = 6f,
            overlaySaturation = if (layer.id == "ice") -1f else 0f,
            maxZoom = 4.5,
            areaPin = areaPin,
            areaRadiusKm = areaRadiusKm,
            highlight = highlight,
            onCameraChange = { camera = it },
            onViewportChange = { viewport = it },
            onMapTap = ::dropPin,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        locateNote?.let { note ->
            Text(
                text = note,
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (!(supportsRange && colormap != null)) {
            layer.legendUrl?.let { legend ->
                RemoteBitmapImage(
                    url = legend,
                    contentDescription = "Legend",
                    modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        layer.legendText?.let { text ->
            Text(
                text = text,
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(text = layer.description, style = RadarType.microPlain, color = RadarColors.ink2)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = "SOURCE NASA GIBS",
                style = RadarType.labelMicro,
                color = CategoryColors.Teal,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = month?.let { "VALID ${it.format(MONTH_FMT).uppercase(Locale.ENGLISH)}" } ?: "LATEST",
                style = RadarType.labelMicro,
                color = RadarColors.ink3,
            )
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showSave) {
        val pinCamera = pendingPin?.let { camera.copy(lat = it.lat, lon = it.lon) } ?: camera
        SaveRegionDialog(
            onDismiss = { showSave = false },
            radiusKm = pinCamera.radiusKm,
            onSave = { name ->
                scope.launch {
                    val saved = container.regions.save(name = name, camera = pinCamera)
                    selectedRegionId = saved.id
                    pendingPin = null
                }
            },
        )
    }
}

@Composable
private fun MonthSlider(
    value: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableIntStateOf(1) }

    fun update(x: Float) {
        if (max <= 0) return
        val fraction = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        onValueChange(((1f - fraction) * max).toInt().coerceIn(0, max))
    }
    Canvas(
        modifier
            .height(24.dp)
            .onSizeChanged { width = it.width }
            .pointerInput(max) {
                detectTapGestures { offset -> update(offset.x) }
            }.pointerInput(max) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    update(change.position.x)
                }
            },
    ) {
        val y = size.height / 2f
        drawLine(
            color = RadarColors.ruleSoft,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
        )
        val fraction = if (max <= 0) 1f else 1f - value.toFloat() / max
        val x = size.width * fraction
        drawCircle(color = RadarColors.ink, radius = 6.dp.toPx(), center = Offset(x, y))
        drawCircle(color = RadarColors.paper2, radius = 3.dp.toPx(), center = Offset(x, y))
    }
}

@Composable
private fun RangeLegend(
    colormap: ClimateColormap,
    low: Float,
    high: Float,
    onLow: (Float) -> Unit,
    onHigh: (Float) -> Unit,
    onClear: () -> Unit,
) {
    val last = colormap.colors.lastIndex.coerceAtLeast(1)
    val lowLabel = colormap.displayValue(colormap.values[(low * last).toInt().coerceIn(0, last)])
    val highLabel = colormap.displayValue(colormap.values[(high * last).toInt().coerceIn(0, last)])
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { width = it.width }
                .pointerInput(low, high, last) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging =
                                if (kotlin.math.abs(offset.x - low * width) <= kotlin.math.abs(offset.x - high * width)) 0 else 1
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                        onDrag = { change, _ ->
                            change.consume()
                            val fraction = (change.position.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            if (dragging == 0) onLow(fraction.coerceAtMost(high - 0.01f)) else onHigh(fraction.coerceAtLeast(low + 0.01f))
                        },
                    )
                },
        ) {
            val segment = size.width / colormap.colors.size.coerceAtLeast(1)
            colormap.colors.forEachIndexed { index, color ->
                drawRect(Color(color), topLeft = Offset(index * segment, 0f), size = Size(segment + 1f, size.height))
            }
            listOf(low, high).forEach { fraction ->
                val x = size.width * fraction
                drawLine(RadarColors.paper2, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
                drawCircle(RadarColors.ink, 6.dp.toPx(), Offset(x, size.height / 2f))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = lowLabel, style = RadarType.labelMicro, color = RadarColors.ink3)
            Spacer(Modifier.weight(1f))
            if (low > 0.01f || high < 0.99f) {
                Text(text = "$lowLabel to $highLabel", style = RadarType.microPlain, color = RadarColors.ink2)
                Spacer(Modifier.width(8.dp))
                FlatButton(text = "Clear range", onClick = onClear)
            } else {
                Text(text = "Drag the ends to pick a range", style = RadarType.microPlain, color = RadarColors.ink3)
            }
            Spacer(Modifier.weight(1f))
            Text(text = highLabel, style = RadarType.labelMicro, color = RadarColors.ink3)
        }
    }
}
