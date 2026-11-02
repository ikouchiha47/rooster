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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RemoteBitmapImage
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.WorldMap
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val MONTH_FMT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

private data class RegionalLayer(
    val id: String,
    val label: String,
    val wmsLayer: String,
    /** Oldest/latest monthly file available; null for a "latest" layer. */
    val start: YearMonth?,
    val latest: YearMonth?,
    val legendUrl: String?,
    val legendText: String? = null,
    val description: String,
)

private val LAYERS =
    listOf(
        RegionalLayer(
            id = "temperature",
            label = "Temperature",
            wmsLayer = "MERRA2_2m_Air_Temperature_Monthly",
            start = YearMonth.of(1980, 1),
            latest = YearMonth.of(2026, 8),
            legendUrl = "https://gibs.earthdata.nasa.gov/legends/MERRA2_2m_Air_Temperature_Monthly_H.png",
            description = "Monthly mean air temperature. Not an anomaly.",
        ),
        RegionalLayer(
            id = "rain",
            label = "Rain",
            wmsLayer = "GLDAS_Surface_Total_Precipitation_Rate_Monthly",
            start = YearMonth.of(2000, 1),
            latest = YearMonth.of(2026, 6),
            legendUrl = "https://gibs.earthdata.nasa.gov/legends/GLDAS_Surface_Total_Precipitation_Rate_Monthly_H.png",
            description = "Monthly total precipitation rate.",
        ),
        RegionalLayer(
            id = "ice",
            label = "Sea ice",
            wmsLayer = "AMSRU2_Sea_Ice_Concentration_12km",
            start = YearMonth.of(2012, 7),
            latest = YearMonth.of(2025, 9),
            legendUrl = null,
            legendText = "White / pale blue = ice concentration.",
            description = "Latest monthly sea-ice concentration available in GIBS.",
        ),
        RegionalLayer(
            id = "fires",
            label = "Fires",
            wmsLayer = "VIIRS_SNPP_Thermal_Anomalies_375m_All",
            start = null,
            latest = null,
            legendUrl = null,
            legendText = "Orange dots = thermal anomalies.",
            description = "Latest thermal anomalies; not a monthly series.",
        ),
    )

private const val GIBS =
    "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi" +
        "?SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0&CRS=EPSG:4326" +
        "&BBOX=-90,-180,90,180&WIDTH=1024&HEIGHT=512&FORMAT=image/png"

private const val BASE_LAYER = "BlueMarble_ShadedRelief_Bathymetry"
private const val OUTLINE_LAYER = "Coastlines_15m"

private fun gibsUrl(
    layer: String,
    month: YearMonth?,
    transparent: Boolean,
): String =
    buildString {
        append(GIBS)
        append("&LAYERS=").append(layer)
        if (transparent) append("&TRANSPARENT=TRUE")
        if (month != null) append("&TIME=").append(month.atDay(1))
    }

/**
 * Fixed regional map viewport. The map is a rendered NASA GIBS WMS image, not a
 * live feed; month and layer are explicit, and every image carries its source
 * and valid period.
 */
@Composable
fun ClimateRegionalTab(modifier: Modifier = Modifier) {
    var layerIndex by remember { mutableIntStateOf(0) }
    var monthOffset by remember { mutableIntStateOf(0) }
    val layer = LAYERS[layerIndex]
    val maxOffset =
        if (layer.start != null && layer.latest != null) {
            ChronoUnit.MONTHS.between(layer.start, layer.latest).toInt()
        } else {
            0
        }
    val month = layer.latest?.minusMonths(monthOffset.toLong())
    val overlayUrl = remember(layer, month) { gibsUrl(layer.wmsLayer, month, transparent = true) }
    val baseUrl = remember { gibsUrl(BASE_LAYER, null, transparent = false) }
    val outlineUrl = remember { gibsUrl(OUTLINE_LAYER, null, transparent = true) }

    Column(modifier.fillMaxSize().background(RadarColors.paper2)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LAYERS.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.width(4.dp))
                FlatButton(
                    text = item.label,
                    onClick = {
                        layerIndex = index
                        monthOffset = 0
                    },
                    solid = index == layerIndex,
                )
            }
        }
        SoftRule()

        if (layer.latest != null && month != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "MONTH", style = RadarType.labelMicro, color = RadarColors.ink3)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = month.format(MONTH_FMT).uppercase(Locale.ENGLISH),
                    style = RadarType.label,
                    color = RadarColors.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text =
                        if (layer.start != null && layer.latest != null) {
                            "${layer.start.year}–${layer.latest.year}"
                        } else {
                            "LATEST"
                        },
                    style = RadarType.labelMicro,
                    color = RadarColors.ink3,
                )
            }
            Slider(
                value = monthOffset.toFloat(),
                onValueChange = { monthOffset = it.toInt().coerceIn(0, maxOffset) },
                valueRange = 0f..maxOffset.toFloat(),
                steps = 0,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            Text(
                text = "LATEST AVAILABLE",
                style = RadarType.labelMicro,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        WorldMap(
            baseUrl = baseUrl,
            overlayUrl = overlayUrl,
            outlineUrl = outlineUrl,
            contentDescription = "${layer.label} regional map",
            modifier = Modifier.fillMaxWidth(),
        )
        layer.legendUrl?.let { legend ->
            RemoteBitmapImage(
                url = legend,
                contentDescription = "Legend",
                modifier = Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        layer.legendText?.let { text ->
            Text(
                text = text,
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(text = layer.description, style = RadarType.microPlain, color = RadarColors.ink2)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = "SOURCE NASA GIBS",
                style = RadarType.labelMicro,
                color = CategoryColors.Teal,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (month != null) "VALID ${month.format(MONTH_FMT).uppercase(Locale.ENGLISH)}" else "LATEST",
                style = RadarType.labelMicro,
                color = RadarColors.ink3,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}
