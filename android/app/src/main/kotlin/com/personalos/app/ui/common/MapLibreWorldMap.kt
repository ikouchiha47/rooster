package com.personalos.app.ui.common

import android.graphics.Bitmap
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
import com.personalos.app.core.map.MapBounds
import com.personalos.app.core.map.MapCamera
import com.personalos.app.core.map.MapViewport
import com.personalos.app.ui.theme.RadarType
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin

const val BLUE_MARBLE_TILES: String =
    "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/" +
        "BlueMarble_ShadedRelief_Bathymetry/default/GoogleMapsCompatible_Level8/{z}/{y}/{x}.jpeg"

data class MapMarker(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
)

data class MapHighlight(
    val bounds: MapBounds,
    val bitmap: Bitmap,
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
    overlaySaturation: Float = 0f,
    maxZoom: Double = 8.0,
    preventWorldRepeat: Boolean = true,
    markers: List<MapMarker> = emptyList(),
    areaPin: MapMarker? = null,
    areaRadiusKm: Double? = null,
    highlight: MapHighlight? = null,
    onCameraChange: (MapCamera) -> Unit,
    onViewportChange: ((MapViewport) -> Unit)? = null,
    onMarkerClick: ((String) -> Unit)? = null,
    onMapTap: ((Double, Double) -> Unit)? = null,
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
    val currentOnMapTap = rememberUpdatedState(onMapTap)
    val currentOnViewport = rememberUpdatedState(onViewportChange)
    val countryLabels =
        remember(context) {
            runCatching {
                context.assets
                    .open("world-countries.json")
                    .bufferedReader()
                    .use { FeatureCollection.fromJson(it.readText()) }
            }.getOrNull()
        }
    val majorCountries =
        remember(countryLabels) {
            countryLabels?.let { collection ->
                FeatureCollection.fromFeatures(
                    collection.features()?.filter { (it.getNumberProperty("rank")?.toInt() ?: 7) <= 2 }.orEmpty(),
                )
            }
        }
    val minorCountries =
        remember(countryLabels) {
            countryLabels?.let { collection ->
                FeatureCollection.fromFeatures(
                    collection.features()?.filter { (it.getNumberProperty("rank")?.toInt() ?: 7) > 2 }.orEmpty(),
                )
            }
        }

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
            mapView.onPause()
            mapView.onStop()
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
                                    log2(width / 1024.0).coerceAtLeast(0.0) + 0.01
                                } else {
                                    0.0
                                }
                            ready.setMinZoomPreference(minZoom)
                            ready.setMaxZoomPreference(maxZoom)
                            ready.addOnCameraIdleListener {
                                currentOnCamera.value(ready.cameraPosition.toMapCamera().clamped())
                                currentOnViewport.value?.invoke(
                                    ready.projection.visibleRegion.latLngBounds.let { bounds ->
                                        MapViewport(
                                            bounds =
                                                MapBounds(
                                                    north = bounds.latitudeNorth,
                                                    east = bounds.longitudeEast,
                                                    south = bounds.latitudeSouth,
                                                    west = bounds.longitudeWest,
                                                ),
                                            width = mapView.width,
                                            height = mapView.height,
                                        )
                                    },
                                )
                            }
                            ready.addOnMapClickListener { latLng ->
                                val features =
                                    ready.queryRenderedFeatures(
                                        ready.projection.toScreenLocation(latLng),
                                        "places-layer",
                                    )
                                val id = features.firstOrNull()?.getStringProperty("id")
                                if (id != null) {
                                    currentOnMarker.value?.invoke(id)
                                    true
                                } else {
                                    currentOnMapTap.value?.invoke(latLng.latitude, latLng.longitude)
                                    currentOnMapTap.value != null
                                }
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

        LaunchedEffect(map, styleReady, overlayTileUrl, overlayMaxZoom, overlaySaturation) {
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
                val overlay =
                    RasterLayer("climate-overlay", "climate-overlay").withProperties(
                        PropertyFactory.rasterOpacity(0.82f),
                        PropertyFactory.rasterResampling("linear"),
                        PropertyFactory.rasterSaturation(overlaySaturation),
                    )
                val below =
                    when {
                        style.getLayer("ocean-tint") != null -> "ocean-tint"
                        style.getLayer("world-country-labels-major") != null -> "world-country-labels-major"
                        style.getLayer("city-labels") != null -> "city-labels"
                        else -> null
                    }
                if (below != null) style.addLayerBelow(overlay, below) else style.addLayer(overlay)
            }
            appliedOverlay = overlayTileUrl
        }

        LaunchedEffect(map, styleReady, majorCountries, minorCountries) {
            val active = map ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            val style = active.style ?: return@LaunchedEffect
            listOf("world-country-labels-major", "world-country-labels-minor").forEach { id ->
                style.getLayer(id)?.let { style.removeLayer(it) }
            }
            listOf("world-countries-major", "world-countries-minor").forEach { id ->
                style.getSource(id)?.let { style.removeSource(it) }
            }
            val major = majorCountries ?: return@LaunchedEffect
            style.addSource(GeoJsonSource("world-countries-major", major))
            style.addLayer(
                SymbolLayer("world-country-labels-major", "world-countries-major")
                    .withProperties(
                        PropertyFactory.textField(Expression.get("name")),
                        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                        PropertyFactory.textSize(10.5f),
                        PropertyFactory.textColor("#10150f"),
                        PropertyFactory.textHaloColor("#FFFCF2"),
                        PropertyFactory.textHaloWidth(2.2f),
                        PropertyFactory.textMaxWidth(8f),
                        PropertyFactory.textPadding(4f),
                    ).apply { setMinZoom(0f) },
            )
            val minor = minorCountries ?: return@LaunchedEffect
            style.addSource(GeoJsonSource("world-countries-minor", minor))
            style.addLayer(
                SymbolLayer("world-country-labels-minor", "world-countries-minor")
                    .withProperties(
                        PropertyFactory.textField(Expression.get("name")),
                        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                        PropertyFactory.textSize(9.5f),
                        PropertyFactory.textColor("#10150f"),
                        PropertyFactory.textHaloColor("#FFFCF2"),
                        PropertyFactory.textHaloWidth(1.8f),
                        PropertyFactory.textMaxWidth(8f),
                        PropertyFactory.textPadding(3f),
                    ).apply { setMinZoom(3f) },
            )
        }

        LaunchedEffect(map, styleReady, camera, minZoom, maxZoom) {
            val active = map ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            val target = camera.clamped().copy(zoom = camera.zoom.coerceIn(minZoom, maxZoom))
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

        LaunchedEffect(map, styleReady, highlight) {
            val active = map ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            val style = active.style ?: return@LaunchedEffect
            style.getLayer("range-highlight-layer")?.let { style.removeLayer(it) }
            style.getSource("range-highlight")?.let { style.removeSource(it) }
            val image = highlight ?: return@LaunchedEffect
            val quad =
                LatLngQuad(
                    LatLng(image.bounds.north, image.bounds.west),
                    LatLng(image.bounds.north, image.bounds.east),
                    LatLng(image.bounds.south, image.bounds.east),
                    LatLng(image.bounds.south, image.bounds.west),
                )
            style.addSource(ImageSource("range-highlight", quad, image.bitmap))
            val layer = RasterLayer("range-highlight-layer", "range-highlight")
            val below = style.getLayer("world-country-labels-major")
            if (below != null) style.addLayerBelow(layer, "world-country-labels-major") else style.addLayer(layer)
        }

        LaunchedEffect(map, styleReady, areaPin, areaRadiusKm) {
            val active = map ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            val style = active.style ?: return@LaunchedEffect
            listOf("area-radius-fill", "area-radius-line", "area-pin").forEach { id ->
                style.getLayer(id)?.let { style.removeLayer(it) }
            }
            listOf("area-radius", "area-pin-point").forEach { id ->
                style.getSource(id)?.let { style.removeSource(it) }
            }
            val pin = areaPin ?: return@LaunchedEffect
            val radius = areaRadiusKm
            if (radius != null) {
                val polygon = circlePolygon(pin.lat, pin.lon, radius)
                style.addSource(GeoJsonSource("area-radius", Feature.fromGeometry(polygon)))
                val fill =
                    org.maplibre.android.style.layers
                        .FillLayer("area-radius-fill", "area-radius")
                        .withProperties(
                            PropertyFactory.fillColor("#F2B233"),
                            PropertyFactory.fillOpacity(0.13f),
                        )
                val line =
                    org.maplibre.android.style.layers
                        .LineLayer("area-radius-line", "area-radius")
                        .withProperties(
                            PropertyFactory.lineColor("#C58A13"),
                            PropertyFactory.lineWidth(1.5f),
                        )
                if (style.getLayer("world-country-labels-major") != null) {
                    style.addLayerBelow(fill, "world-country-labels-major")
                    style.addLayerBelow(line, "world-country-labels-major")
                } else {
                    style.addLayer(fill)
                    style.addLayer(line)
                }
            }
            style.addSource(
                GeoJsonSource(
                    "area-pin-point",
                    Feature.fromGeometry(Point.fromLngLat(pin.lon, pin.lat)),
                ),
            )
            style.addLayer(
                CircleLayer("area-pin", "area-pin-point").withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor("#B3261E"),
                    PropertyFactory.circleStrokeColor("#FFFCF2"),
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

private fun circlePolygon(
    lat: Double,
    lon: Double,
    radiusKm: Double,
): Polygon {
    val latDelta = radiusKm / 111.0
    val lonDelta = radiusKm / (111.0 * cos(lat * PI / 180.0).coerceAtLeast(0.1))
    val points =
        (0..64).map { index ->
            val angle = 2.0 * PI * index / 64.0
            Point.fromLngLat(
                lon + lonDelta * cos(angle),
                (lat + latDelta * sin(angle)).coerceIn(-85.0, 85.0),
            )
        }
    return Polygon.fromLngLats(listOf(points))
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
      "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
      "sources": {
        "base": {
          "type": "raster",
          "tiles": ["$BLUE_MARBLE_TILES"],
          "tileSize": 256,
          "minzoom": 0,
          "maxzoom": 8
        },
        "labels": {
          "type": "vector",
          "url": "https://tiles.openfreemap.org/planet"
        }
      },
      "layers": [
        {"id":"base","type":"raster","source":"base"},
        {
          "id":"ocean-tint",
          "type":"fill",
          "source":"labels",
          "source-layer":"water",
          "paint":{"fill-color":"#21627F","fill-opacity":0.30}
        },
        {
          "id":"coastline",
          "type":"line",
          "source":"labels",
          "source-layer":"water",
          "paint":{"line-color":"#102C36","line-width":1.1,"line-opacity":0.72}
        },
        {
          "id":"country-borders",
          "type":"line",
          "source":"labels",
          "source-layer":"boundary",
          "filter":["==",["get","admin_level"],2],
          "paint":{"line-color":"rgba(24,30,24,0.7)","line-width":0.9}
        },
        {
          "id":"city-labels",
          "type":"symbol",
          "source":"labels",
          "source-layer":"place",
          "filter":["in",["get","class"],["literal",["city","town"]]],
          "layout":{
            "text-field":["coalesce",["get","name:en"],["get","name"]],
            "text-font":["Noto Sans Regular"],
            "text-size":["interpolate",["linear"],["zoom"],2,10,4,12,6,14],
            "text-max-width":10,
            "text-padding":3
          },
          "paint":{
            "text-color":"#31362e",
            "text-halo-color":"rgba(247,243,232,0.9)",
            "text-halo-width":1.2
          }
        }
      ]
    }
    """.trimIndent()
