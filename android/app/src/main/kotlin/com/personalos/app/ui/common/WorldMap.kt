package com.personalos.app.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The shared world-map canvas: one base image, one transparent data overlay,
 * and one outline layer. Rendering is stacked in that order so every climate
 * layer sits on a visible Earth instead of a floating transparent cut-out.
 *
 * This is the static-image implementation; the same API can later be backed by
 * MapLibre tiles without changing the call sites.
 */
@Composable
fun WorldMap(
    baseUrl: String,
    overlayUrl: String?,
    outlineUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val base by remoteBitmap(baseUrl)
    val overlay by remoteBitmap(overlayUrl)
    val outline by remoteBitmap(outlineUrl)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .background(RadarColors.paper3),
    ) {
        base?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        overlay?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        outline?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (base == null) {
            androidx.compose.material3.Text(
                text = "Map unavailable.",
                style = com.personalos.app.ui.theme.RadarType.microPlain,
                color = RadarColors.ink3,
                modifier = Modifier.fillMaxWidth().background(RadarColors.paper3).padding(8.dp),
            )
        }
    }
}

@Composable
fun RemoteBitmapImage(
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val bitmap by remoteBitmap(url)
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.FillWidth,
            modifier = modifier,
        )
    }
}

@Composable
private fun remoteBitmap(url: String?): State<Bitmap?> {
    val state = remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        state.value = withContext(Dispatchers.IO) { fetchBitmap(url) }
    }
    return state
}

private fun fetchBitmap(url: String): Bitmap? =
    runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "PersonalRadar/0.1 (personal use)")
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
