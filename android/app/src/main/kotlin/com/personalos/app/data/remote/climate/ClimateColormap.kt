package com.personalos.app.data.remote.climate

import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class ClimateColormap(
    val colors: List<Int>,
    val values: List<Double>,
    val unit: String,
    val indexByRgb: Map<Int, Int>,
) {
    val min: Double get() = values.firstOrNull() ?: 0.0
    val max: Double get() = values.lastOrNull() ?: 1.0

    fun displayValue(value: Double): String =
        if (unit.equals("K", ignoreCase = true)) {
            "${value - 273.15}".let { if (it.length > 5) it.substring(0, 5) else it } + "°C"
        } else {
            "${"%.1f".format(value)} $unit"
        }
}

suspend fun fetchClimateColormap(
    layer: String,
    fetch: suspend (String) -> String? = ::fetchText,
): ClimateColormap? {
    val xml = fetch("https://gibs.earthdata.nasa.gov/colormaps/v1.3/$layer.xml") ?: return null
    val document =
        runCatching {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        }.getOrNull() ?: return null
    val root = document.documentElement ?: return null
    val maps = root.getElementsByTagName("ColorMap")
    for (index in 0 until maps.length) {
        val map = maps.item(index) as? Element ?: continue
        val title = map.getAttribute("title")
        if (title.equals("No Data", ignoreCase = true)) continue
        val unit = map.getAttribute("units").ifBlank { "" }
        val colors = mutableListOf<Int>()
        val values = mutableListOf<Double>()
        val byRgb = mutableMapOf<Int, Int>()
        val entries = map.getElementsByTagName("ColorMapEntry")
        for (entryIndex in 0 until entries.length) {
            val entry = entries.item(entryIndex) as? Element ?: continue
            if (entry.getAttribute("transparent") == "true" || entry.getAttribute("nodata") == "true") continue
            val rgb = parseRgb(entry.getAttribute("rgb")) ?: continue
            val raw = entry.getAttribute("sourceValue").ifBlank { entry.getAttribute("value") }
            val value = firstNumber(raw) ?: continue
            byRgb[rgb] = colors.size
            colors += rgb
            values += value
        }
        if (colors.isNotEmpty()) return ClimateColormap(colors, values, unit, byRgb)
    }
    return null
}

private fun parseRgb(raw: String): Int? {
    val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
    if (parts.size != 3) return null
    return (0xFF shl 24) or (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
}

private fun firstNumber(raw: String): Double? = Regex("""-?\d+(?:\.\d+)?""").find(raw)?.value?.toDoubleOrNull()

private suspend fun fetchText(url: String): String? =
    runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "PersonalRadar/0.1 (personal use)")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
