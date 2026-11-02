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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.personalos.app.ui.common.CompactValueSlider
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.MapLibreWorldMap
import com.personalos.app.ui.common.MapLine
import com.personalos.app.ui.common.MapMarker
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RegionControls
import com.personalos.app.ui.common.SaveRegionDialog
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private data class WeatherLayer(
    val id: String,
    val label: String,
    val gibsLayer: String,
    val description: String,
)

private val WEATHER_LAYERS =
    listOf(
        WeatherLayer("places", "Places", "", "Configured weather places from the store."),
        WeatherLayer("rain", "Rain", "IMERG_Precipitation_Rate", "Latest daily precipitation rate."),
        WeatherLayer("wind", "Wind", "", "Wind at configured places: line shows the direction it is blowing toward."),
    )

private val DAY_FMT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

data class WeatherPin(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val temperatureC: Int?,
    val windKph: Int? = null,
    val windDirection: Int? = null,
    val pressureHpa: Int? = null,
)

/**
 * Regional weather is the same map engine as Climate, not a second renderer.
 * It draws the configured weather places and shared saved regions; it does not
 * invent a gridded value where the app has none.
 */
@Composable
fun WeatherRegionalTab(
    places: List<WeatherPin>,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val regions by container.regions.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val weatherDefault by
        container.regions
            .observeDefault(RegionConsumers.WEATHER)
            .collectAsStateWithLifecycle(initialValue = null)
    val homeDefault by
        container.regions
            .observeDefault(RegionConsumers.HOME)
            .collectAsStateWithLifecycle(initialValue = null)

    var camera by remember { mutableStateOf(MapCamera(20.0, 0.0, 0.1)) }
    var selectedRegionId by remember { mutableStateOf<String?>(null) }
    var selectedPlaceId by remember { mutableStateOf<String?>(null) }
    var initialised by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var locateNote by remember { mutableStateOf<String?>(null) }
    var placingPin by remember { mutableStateOf(false) }
    var pendingPin by remember { mutableStateOf<MapMarker?>(null) }
    var layerIndex by remember { mutableStateOf(0) }
    var dayOffset by remember { mutableStateOf(0) }

    val selectedPlace = places.firstOrNull { it.id == selectedPlaceId }
    val selectedRegion = regions.firstOrNull { it.id == selectedRegionId }
    val areaPin =
        pendingPin
            ?: selectedRegion?.let {
                MapMarker(it.id, it.name, it.camera.lat, it.camera.lon)
            }
    val areaRadiusKm = if (pendingPin != null) camera.radiusKm else selectedRegion?.radiusKm
    val layer = WEATHER_LAYERS[layerIndex]
    val date = LocalDate.now().minusDays(1L + dayOffset)
    val showWind = layer.id == "wind"
    val windLines =
        if (!showWind) {
            emptyList()
        } else {
            places.mapNotNull { place ->
                val direction = place.windDirection ?: return@mapNotNull null
                val radians = (direction + 180.0) * PI / 180.0
                MapLine(
                    id = place.id,
                    startLat = place.lat,
                    startLon = place.lon,
                    endLat = place.lat + cos(radians) * 0.7,
                    endLon = place.lon + sin(radians) * 0.7,
                )
            }
        }
    val overlayUrl =
        if (layer.gibsLayer.isBlank()) {
            null
        } else {
            "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/${layer.gibsLayer}/" +
                "default/$date/GoogleMapsCompatible_Level6/{z}/{y}/{x}.png"
        }

    LaunchedEffect(weatherDefault, homeDefault, regions, places) {
        if (initialised) return@LaunchedEffect
        val start = weatherDefault ?: homeDefault ?: regions.firstOrNull()
        if (start != null) {
            camera = start.camera
            selectedRegionId = start.id
            initialised = true
        } else if (places.isNotEmpty() || regions.isNotEmpty() || weatherDefault != null || homeDefault != null) {
            camera =
                places.firstOrNull()?.let {
                    camera.copy(lat = it.lat, lon = it.lon, zoom = 4.0)
                } ?: camera
            initialised = true
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
        camera = weatherDefault?.camera ?: homeDefault?.camera ?: MapCamera(20.0, 0.0, 0.1)
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
        RegionControls(
            regions = regions,
            defaultRegion = weatherDefault ?: homeDefault,
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
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WEATHER_LAYERS.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.width(4.dp))
                FlatButton(
                    text = item.label,
                    onClick = {
                        layerIndex = index
                        dayOffset = 0
                    },
                    solid = index == layerIndex,
                )
            }
        }
        if (layer.gibsLayer.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Text(
                    text = date.format(DAY_FMT).uppercase(Locale.ENGLISH),
                    style = RadarType.label,
                    color = RadarColors.ink,
                    modifier = Modifier.width(112.dp),
                )
                CompactValueSlider(
                    value = dayOffset,
                    max = 6,
                    onValueChange = { dayOffset = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (placingPin) {
            androidx.compose.material3.Text(
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
                androidx.compose.material3.Text(
                    text = "Pin dropped",
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                )
                Spacer(Modifier.width(8.dp))
                com.personalos.app.ui.common.FlatButton(
                    text = "Save pin",
                    onClick = { showSave = true },
                )
                Spacer(Modifier.width(4.dp))
                com.personalos.app.ui.common
                    .FlatButton(text = "Cancel", onClick = { pendingPin = null })
            }
        }

        MapLibreWorldMap(
            camera = camera,
            overlayTileUrl = overlayUrl,
            overlayMaxZoom = 6f,
            maxZoom = 6.0,
            mapLines = windLines,
            markers =
                places.map {
                    MapMarker(
                        id = it.id,
                        label = it.name,
                        lat = it.lat,
                        lon = it.lon,
                    )
                },
            areaPin = areaPin,
            areaRadiusKm = areaRadiusKm,
            onCameraChange = { camera = it },
            onMapTap = ::dropPin,
            onMarkerClick = { id ->
                selectedPlaceId = id
                places.firstOrNull { it.id == id }?.let {
                    selectedRegionId = null
                    camera = camera.copy(lat = it.lat, lon = it.lon, zoom = max(camera.zoom, 4.0))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            androidx.compose.material3.Text(
                text = layer.description,
                style = RadarType.microPlain,
                color = RadarColors.ink2,
                modifier = Modifier.weight(1f),
            )
        }
        if (layer.gibsLayer.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                androidx.compose.material3.Text(
                    text = "SOURCE NASA GIBS",
                    style = RadarType.labelMicro,
                    color = CategoryColors.Teal,
                )
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.Text(
                    text = "VALID ${date.format(DAY_FMT).uppercase(Locale.ENGLISH)}",
                    style = RadarType.labelMicro,
                    color = RadarColors.ink3,
                )
            }
        }

        locateNote?.let { note ->
            androidx.compose.material3.Text(
                text = note,
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        selectedPlace?.let { place ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text(
                        text = place.name,
                        style = RadarType.label,
                        color = RadarColors.ink,
                    )
                    androidx.compose.material3.Text(
                        text =
                            buildString {
                                append("%.2f".format(place.lat))
                                append(", ")
                                append("%.2f".format(place.lon))
                                if (place.windKph != null) {
                                    append("  ·  wind ")
                                    append(place.windKph)
                                    append(" km/h")
                                    place.windDirection?.let { append(" from ${compass(it)}") }
                                }
                                place.pressureHpa?.let { append("  ·  $it hPa") }
                            },
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                    )
                }
                androidx.compose.material3.Text(
                    text = place.temperatureC?.let { "$it°" } ?: "—",
                    style = RadarType.monoH2,
                    color = RadarColors.ink,
                )
            }
        }

        androidx.compose.material3.Text(
            text =
                if (places.isEmpty()) {
                    "No weather places configured. Add one from Weather."
                } else {
                    "Tap a place marker to centre it. Saved views are shared with Climate."
                },
            style = RadarType.microPlain,
            color = RadarColors.ink3,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
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

private fun compass(degrees: Int): String {
    val points = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[((degrees % 360 + 360) % 360 + 22) / 45 % 8]
}
