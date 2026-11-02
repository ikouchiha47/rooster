package com.personalos.app.core.hazard

/**
 * One seismic event from the USGS "all earthquakes, past day" feed.
 *
 * [magnitude] is null when USGS has not assigned one yet. [depth] is the
 * hypocentre depth in kilometres, and null when the feed omits the altitude.
 */
data class Quake(
    val id: String,
    val magnitude: Double?,
    val place: String,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val depth: Double?,
    val url: String,
)
