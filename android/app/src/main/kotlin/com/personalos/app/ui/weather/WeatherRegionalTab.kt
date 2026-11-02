package com.personalos.app.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.MapLibreWorldMap
import com.personalos.app.ui.common.MapMarker
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RegionControls
import com.personalos.app.ui.common.SaveRegionDialog
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.math.max

data class WeatherPin(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val temperatureC: Int?,
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

    val selectedPlace = places.firstOrNull { it.id == selectedPlaceId }

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
    }

    fun clearSelection() {
        selectedRegionId = null
        camera = weatherDefault?.camera ?: homeDefault?.camera ?: MapCamera(20.0, 0.0, 0.1)
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
            onSaveView = { showSave = true },
            onLocate = ::locate,
            onClear = ::clearSelection,
            locating = locating,
        )
        androidx.compose.material3.Text(
            text = "Save area stores this pin and the radius implied by the current zoom; pick it later or make it Home in Settings.",
            style = RadarType.microPlain,
            color = RadarColors.ink3,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        MapLibreWorldMap(
            camera = camera,
            overlayTileUrl = null,
            markers =
                places.map {
                    MapMarker(
                        id = it.id,
                        label = it.name,
                        lat = it.lat,
                        lon = it.lon,
                    )
                },
            onCameraChange = { camera = it },
            onMarkerClick = { id ->
                selectedPlaceId = id
                places.firstOrNull { it.id == id }?.let {
                    selectedRegionId = null
                    camera = camera.copy(lat = it.lat, lon = it.lon, zoom = max(camera.zoom, 4.0))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )

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
                        text = "${"%.2f".format(place.lat)}, ${"%.2f".format(place.lon)}",
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
