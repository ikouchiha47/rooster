package com.personalos.app.data.taxonomy

import android.content.Context
import com.personalos.app.core.tag.taxonomy.Taxonomy
import com.personalos.app.core.tag.taxonomy.TaxonomyJson
import com.personalos.app.core.tag.taxonomy.mergeTaxonomies
import com.personalos.mlsdk.ModelPaths
import java.io.File

/**
 * Loads the one taxonomy the app runs on: the bundled asset, with a downloaded
 * copy merged over it (by leaf id). The data layer owns this because reading the
 * asset needs a [Context]; core stays android-free and just consumes the result.
 *
 * Every consumer — the tag vocabulary, the [com.personalos.app.core.tag.TagSchema]
 * (groups/owners), the feed categories — derives from this one object, so a
 * downloaded taxonomy updates all of them together.
 */
object TaxonomyLoader {
    const val ASSET = "taxonomy/taxonomy.v1.json"

    fun load(
        context: Context,
        downloadedFileName: String,
    ): Taxonomy? {
        val base = readAsset(context) ?: return null
        val overlay = readFile(ModelPaths.file(context, downloadedFileName))
        return if (overlay != null) mergeTaxonomies(base, overlay) else base
    }

    private fun readAsset(context: Context): Taxonomy? =
        runCatching {
            val text = context.assets.open(ASSET).use { it.readBytes().decodeToString() }
            TaxonomyJson.parse(text)
        }.getOrNull()

    private fun readFile(file: File): Taxonomy? =
        if (!file.isFile) {
            null
        } else {
            runCatching { TaxonomyJson.parse(file.readText()) }.getOrNull()
        }
}
