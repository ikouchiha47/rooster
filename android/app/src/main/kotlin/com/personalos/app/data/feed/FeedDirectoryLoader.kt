package com.personalos.app.data.feed

import android.content.Context
import com.personalos.app.core.feed.FeedDirectory
import com.personalos.app.core.feed.FeedDirectoryJson
import com.personalos.mlsdk.ModelPaths
import java.io.File

/**
 * Loads the feed directory from the bundled asset, with a downloaded copy layered
 * over it — the same shape as the taxonomy, so the directory updates without a
 * release. Data layer because reading the asset needs a [Context].
 */
object FeedDirectoryLoader {
    const val ASSET = "taxonomy/feeds.v1.json"

    fun load(
        context: Context,
        downloadedFileName: String? = null,
    ): FeedDirectory? {
        val base = readAsset(context) ?: return null
        val overlay = downloadedFileName?.let { readFile(ModelPaths.file(context, it)) }
        // Overlay wins per category (a downloaded directory replaces the bundled one).
        return overlay ?: base
    }

    private fun readAsset(context: Context): FeedDirectory? =
        runCatching {
            val text = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
            FeedDirectoryJson.parse(text)
        }.getOrNull()

    private fun readFile(file: File): FeedDirectory? = if (!file.isFile) null else runCatching { FeedDirectoryJson.parse(file.readText()) }.getOrNull()
}
