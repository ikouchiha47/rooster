package com.personalos.app.core.map

/**
 * The map viewport. This is not the device camera and not street-level imagery;
 * it is only where the renderer is looking.
 */
data class MapCamera(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val bearing: Double = 0.0,
    val pitch: Double = 0.0,
) {
    fun normalised(): MapCamera =
        copy(
            lat = lat.coerceIn(-MAX_LAT, MAX_LAT),
            lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0,
            zoom = zoom.coerceIn(0.0, MAX_ZOOM),
            bearing = ((bearing % 360.0) + 360.0) % 360.0,
            pitch = pitch.coerceIn(0.0, 60.0),
        )

    private companion object {
        const val MAX_LAT = 85.05112878
        const val MAX_ZOOM = 20.0
    }
}

/** A named camera that any regional surface may reuse. */
data class SavedRegion(
    val id: String,
    val name: String,
    val camera: MapCamera,
    val sourceKind: String? = null,
    val sourceId: String? = null,
    val createdAt: Long = 0L,
)

object RegionConsumers {
    const val HOME = "home"
    const val CLIMATE = "climate"
    const val WEATHER = "weather"
}
