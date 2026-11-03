package com.personalos.app.core.hazard

import com.personalos.app.core.map.MapBounds
import com.personalos.app.core.map.MapCamera
import com.personalos.app.core.map.SavedRegion

/** An area filter: map bounds, a dropped pin with radius, or a saved region. */
sealed interface HazardArea {
    data class Bounds(
        val bounds: MapBounds,
    ) : HazardArea

    data class Pin(
        val camera: MapCamera,
        val radiusKm: Double,
    ) : HazardArea

    data class Saved(
        val region: SavedRegion,
    ) : HazardArea
}
