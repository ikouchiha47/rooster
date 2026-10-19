package com.personalos.app.data.taxonomy

import com.personalos.app.core.tag.BundledVocabulary
import com.personalos.app.core.tag.TagDefinition
import com.personalos.app.core.tag.TagVocabularySource
import com.personalos.app.core.tag.taxonomy.Taxonomy
import com.personalos.app.core.tag.taxonomy.source

/**
 * The vocabulary as a **live** source: it reads the taxonomy (bundled asset plus
 * downloaded overlay) each time [definitions] is called.
 *
 * That indirection is the whole point. A source built once from a taxonomy read at
 * construction cannot see a file downloaded later — `invalidate()` would rebuild
 * the cache from the same stale facts. Reading here means a download takes effect
 * on the next rebuild, with no restart.
 */
class TaxonomyVocabularySource(
    private val read: () -> Taxonomy?,
) : TagVocabularySource {
    override val id: String = "taxonomy"

    override fun definitions(): List<TagDefinition> = read()?.source()?.definitions() ?: BundledVocabulary.definitions()
}
