package com.personalos.app.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.map.MapCamera
import com.personalos.app.core.map.RegionConsumers
import com.personalos.app.core.map.SavedRegion
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.MapLibreWorldMap
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RegionControls
import com.personalos.app.ui.common.RemoteBitmapImage
import com.personalos.app.ui.common.SaveRegionDialog
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
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
            legendText = "White / pale blue = ice concentration.",
            description = "Latest monthly sea-ice concentration available in GIBS.",
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
    var camera by remember { mutableStateOf(MapCamera(20.0, 0.0, 1.2)) }
    var selectedRegionId by remember { mutableStateOf<String?>(null) }
    var initialised by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var locateNote by remember { mutableStateOf<String?>(null) }

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

    fun selectRegion(region: SavedRegion) {
        selectedRegionId = region.id
        camera = region.camera
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
                    onClick = {
                        layerIndex = index
                        monthOffset = 0
                    },
                    solid = index == layerIndex,
                )
            }
        }
        SoftRule()

        RegionControls(
            regions = regions,
            defaultRegion = climateDefault ?: homeDefault,
            selectedId = selectedRegionId,
            onSelect = ::selectRegion,
            onSaveView = { showSave = true },
            onLocate = ::locate,
            locating = locating,
        )

        if (month != null && layer.start != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "MONTH", style = RadarType.labelMicro, color = RadarColors.ink3)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = month.format(MONTH_FMT).uppercase(Locale.ENGLISH),
                    style = RadarType.label,
                    color = RadarColors.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${layer.start.year}–${layer.latest.year}",
                    style = RadarType.labelMicro,
                    color = RadarColors.ink3,
                )
            }
            Slider(
                value = monthOffset.toFloat(),
                onValueChange = { monthOffset = it.toInt().coerceIn(0, maxOffset) },
                valueRange = 0f..maxOffset.toFloat(),
                steps = 0,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            Text(
                text = "LATEST AVAILABLE",
                style = RadarType.labelMicro,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        MapLibreWorldMap(
            camera = camera,
            overlayTileUrl = overlayUrl,
            overlayMaxZoom = 6f,
            maxZoom = 5.0,
            onCameraChange = { camera = it },
            modifier = Modifier.fillMaxWidth(),
        )

        locateNote?.let { note ->
            Text(
                text = note,
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        layer.legendUrl?.let { legend ->
            RemoteBitmapImage(
                url = legend,
                contentDescription = "Legend",
                modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp, vertical = 2.dp),
            )
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
        SaveRegionDialog(
            onDismiss = { showSave = false },
            onSave = { name ->
                scope.launch {
                    val saved = container.regions.save(name = name, camera = camera)
                    selectedRegionId = saved.id
                }
            },
        )
    }
}
