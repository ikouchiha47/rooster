package com.personalos.app.data.remote.climate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.personalos.app.core.map.MapBounds
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

suspend fun fetchRangeBitmap(
    layer: String,
    date: String,
    bounds: MapBounds,
    width: Int,
    height: Int,
    colormap: ClimateColormap,
    lowIndex: Int,
    highIndex: Int,
): Bitmap? {
    if (width <= 0 || height <= 0 || lowIndex > highIndex) return null
    val source =
        runCatching {
            fetchBitmap(wmsUrl(layer, date, bounds, width, height))
        }.getOrNull() ?: return null
    return filterBitmap(source, colormap, lowIndex, highIndex)
}

private fun wmsUrl(
    layer: String,
    date: String,
    bounds: MapBounds,
    width: Int,
    height: Int,
): String {
    val min = project(bounds.south, bounds.west)
    val max = project(bounds.north, bounds.east)
    return "https://gibs.earthdata.nasa.gov/wms/epsg3857/best/wms.cgi" +
        "?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0&CRS=EPSG:3857" +
        "&BBOX=${min.first},${min.second},${max.first},${max.second}" +
        "&WIDTH=$width&HEIGHT=$height&LAYERS=$layer&FORMAT=image/png" +
        "&TRANSPARENT=TRUE&TIME=$date"
}

private fun project(
    lat: Double,
    lon: Double,
): Pair<Double, Double> {
    val x = lon * 20037508.34 / 180.0
    val y = ln(tan((90.0 + lat.coerceIn(-85.0, 85.0)) * PI / 360.0)) * 20037508.34 / PI
    return x to y
}

private fun filterBitmap(
    source: Bitmap,
    colormap: ClimateColormap,
    low: Int,
    high: Int,
): Bitmap {
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    for (index in pixels.indices) {
        val pixel = pixels[index]
        if ((pixel ushr 24) == 0) continue
        val rgb = pixel and 0x00FFFFFF
        val band = colormap.indexByRgb[rgb]
        if (band == null || band < low || band > high) {
            pixels[index] = 0
        }
    }
    return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
}

private suspend fun fetchBitmap(url: String): Bitmap? =
    runCatching {
        withHttp(url) { connection ->
            BitmapFactory.decodeStream(connection.inputStream)
        }
    }.getOrNull()

private fun <T> withHttp(
    url: String,
    block: (HttpURLConnection) -> T,
): T {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("User-Agent", "PersonalRadar/0.1 (personal use)")
        block(connection)
    } finally {
        connection.disconnect()
    }
}
