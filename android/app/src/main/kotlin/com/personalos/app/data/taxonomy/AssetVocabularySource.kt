package com.personalos.app.data.taxonomy

import android.content.Context
import com.personalos.app.core.tag.TagDefinition
import com.personalos.app.core.tag.TagVocabularySource
import com.personalos.app.core.tag.taxonomy.TaxonomyJson
import com.personalos.app.core.tag.taxonomy.source

/**
 * The bundled taxonomy, read from the APK's assets. This is the cold-start
 * vocabulary: it needs no network and no download.
 *
 * The asset is read and parsed once, lazily, on first use — the callers
 * (`TagVocabulary.lexicon`/`descriptions`) already run off the main thread.
 */
class AssetVocabularySource(
    private val context: Context,
    private val assetPath: String = DEFAULT_ASSET,
) : TagVocabularySource {
    private val delegate: TagVocabularySource by lazy {
        val text = context.assets.open(assetPath).use { it.readBytes().decodeToString() }
        TaxonomyJson.parse(text).source()
    }

    override val id: String get() = delegate.id

    override fun definitions(): List<TagDefinition> = delegate.definitions()

    companion object {
        const val DEFAULT_ASSET = "taxonomy/taxonomy.v1.json"
    }
}
