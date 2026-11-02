package com.personalos.app.data.remote

import com.personalos.app.core.hazard.HazardWindow
import com.personalos.app.core.hazard.Quake
import com.personalos.app.core.hazard.QuakeGeoJsonParser
import com.personalos.app.core.hazard.QuakeSource
import com.personalos.app.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** USGS earthquakes, past day. Free and keyless; no account or quota. */
class UsgsEarthquakeSource(
    private val url: String = URL,
) : QuakeSource {
    override val id: String = SOURCE_ID

    override suspend fun fetch(window: HazardWindow): List<Quake> =
        withContext(Dispatchers.IO) {
            QuakeGeoJsonParser.parse(Http.getText(urlFor(window), accept = ACCEPT))
        }

    private fun urlFor(window: HazardWindow): String = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/${window.feed}.geojson"

    companion object {
        const val SOURCE_ID = "usgs-earthquakes"
        const val URL = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson"

        private const val ACCEPT = "application/geo+json, application/json"
    }
}
