package com.personalos.app.ui.common

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.personalos.app.core.map.MapCamera
import com.personalos.app.ui.theme.RadarType
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.log2

const val BLUE_MARBLE_TILES: String =
    "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/" +
        "BlueMarble_ShadedRelief_Bathymetry/default/GoogleMapsCompatible_Level8/{z}/{y}/{x}.jpeg"

data class MapMarker(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
)

/**
 * The shared regional map. Camera is only a viewport (lat/lon/zoom/bearing/pitch),
 * never a device camera or street-level view. The caller owns the camera state so
 * Home/saved regions can animate it.
 */
@Composable
fun MapLibreWorldMap(
    camera: MapCamera,
    overlayTileUrl: String?,
    overlayMaxZoom: Float = 6f,
    maxZoom: Double = 8.0,
    preventWorldRepeat: Boolean = true,
    markers: List<MapMarker> = emptyList(),
    onCameraChange: (MapCamera) -> Unit,
    onMarkerClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    remember {
        if (!MapLibre.hasInstance()) {
            MapLibre.getInstance(context, null, WellKnownTileServer.MapLibre)
        }
    }
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var appliedOverlay by remember { mutableStateOf<String?>(null) }
    var cameraInitialised by remember { mutableStateOf(false) }
    var minZoom by remember { mutableStateOf(0.0) }
    val currentOnCamera = rememberUpdatedState(onCameraChange)
    val currentOnMarker = rememberUpdatedState(onMarkerClick)

    DisposableEffect(lifecycleOwner, mapView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.45f)
            .background(RadarColors.paper3),
    ) {
        AndroidView(
            factory = {
                mapView.apply {
                    onCreate(Bundle())
                    onStart()
                    onResume()
                    getMapAsync { ready ->
                        map = ready
                        ready.setStyle(Style.Builder().fromJson(baseStyleJson())) { style ->
                            styleReady = true
                            val width =
                                mapView.width.takeIf { it > 0 }
                                    ?: context.resources.displayMetrics.widthPixels
                            minZoom =
                                if (preventWorldRepeat) {
                                    log2(width / 512.0).coerceAtLeast(0.0) + 0.5
                                } else {
                                    0.0
                                }
                            ready.setMinZoomPreference(minZoom)
                            ready.setMaxZoomPreference(maxZoom)
                            ready.addOnCameraIdleListener {
                                currentOnCamera.value(ready.cameraPosition.toMapCamera().clamped())
                            }
                            ready.addOnMapClickListener { latLng ->
                                val features =
                                    ready.queryRenderedFeatures(
                                        ready.projection.toScreenLocation(latLng),
                                        "places-layer",
                                    )
                                val id = features.firstOrNull()?.getStringProperty("id")
                                if (id != null) currentOnMarker.value?.invoke(id)
                                id != null
                            }
                            ready.cameraPosition =
                                camera.copy(zoom = camera.zoom.coerceIn(minZoom, maxZoom)).toCameraPosition()
                            cameraInitialised = true
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        LaunchedEffect(map, styleReady, overlayTileUrl, overlayMaxZoom) {
            val active = map ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            if (appliedOverlay == overlayTileUrl) return@LaunchedEffect
            val style = active.style ?: return@LaunchedEffect
            style.getLayer("climate-overlay")?.let { style.removeLayer(it) }
            style.getSource("climate-overlay")?.let { style.removeSource(it) }
            if (overlayTileUrl != null) {
                val tileSet =
                    TileSet("2.1.0", overlayTileUrl).apply {
                        this.minZoom = 0f
                        this.maxZoom = overlayMaxZoom
                    }
                style.addSource(RasterSource("climate-overlay", tileSet, 256))
                style.addLayer(RasterLayer("climate-overlay", "climate-overlay"))
            }
            appliedOverlay = overlayTileUrl
        }

        LaunchedEffect(map, styleReady, camera) {
            val active = map ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            val target = camera.normalised()
            if (!cameraInitialised) {
                active.cameraPosition = target.toCameraPosition()
                cameraInitialised = true
                return@LaunchedEffect
            }
            val current = active.cameraPosition.toMapCamera()
            if (current.closeTo(target)) return@LaunchedEffect
            active.animateCamera(CameraUpdateFactory.newCameraPosition(target.toCameraPosition()), 550)
        }

        LaunchedEffect(map, styleReady, markers) {
            val active = map ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            val style = active.style ?: return@LaunchedEffect
            style.getLayer("places-layer")?.let { style.removeLayer(it) }
            style.getSource("places-source")?.let { style.removeSource(it) }
            if (markers.isEmpty()) return@LaunchedEffect
            val features =
                markers.map { marker ->
                    Feature.fromGeometry(Point.fromLngLat(marker.lon, marker.lat)).apply {
                        addStringProperty("id", marker.id)
                        addStringProperty("label", marker.label)
                    }
                }
            style.addSource(GeoJsonSource("places-source", FeatureCollection.fromFeatures(features)))
            style.addLayer(
                CircleLayer("places-layer", "places-source").withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor("#F2B233"),
                    PropertyFactory.circleStrokeColor("#FFFDF7"),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
        }

        androidx.compose.material3.Text(
            text = "NASA GIBS · MAPLIBRE",
            style = RadarType.labelMicro,
            color = RadarColors.ink3,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .background(RadarColors.paper2)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

private fun MapCamera.toCameraPosition(): CameraPosition =
    CameraPosition
        .Builder()
        .target(LatLng(lat, lon))
        .zoom(zoom)
        .bearing(bearing)
        .tilt(pitch)
        .build()

private fun CameraPosition.toMapCamera(): MapCamera =
    MapCamera(
        lat = target?.latitude ?: 0.0,
        lon = target?.longitude ?: 0.0,
        zoom = zoom,
        bearing = bearing,
        pitch = tilt,
    )

private fun MapCamera.closeTo(other: MapCamera): Boolean =
    abs(lat - other.lat) < 0.0001 &&
        abs(lon - other.lon) < 0.0001 &&
        abs(zoom - other.zoom) < 0.01 &&
        abs(bearing - other.bearing) < 0.1 &&
        abs(pitch - other.pitch) < 0.1

private fun baseStyleJson(): String =
    """
    {
      "version": 8,
      "sources": {
        "base": {
          "type": "raster",
          "tiles": ["$BLUE_MARBLE_TILES"],
          "tileSize": 256,
          "minzoom": 0,
          "maxzoom": 8
        }
      },
      "layers": [
        {"id":"base","type":"raster","source":"base"}
      ]
    }
    """.trimIndent()
