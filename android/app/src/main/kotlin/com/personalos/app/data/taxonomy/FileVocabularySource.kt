package com.personalos.app.data.taxonomy

import com.personalos.app.core.tag.TagDefinition
import com.personalos.app.core.tag.TagVocabularySource
import com.personalos.app.core.tag.taxonomy.TaxonomyJson
import com.personalos.app.core.tag.taxonomy.source
import java.io.File

/**
 * A downloaded taxonomy, layered over the bundled one. Read on each call — the
 * file is a few KB, and [com.personalos.app.core.tag.TagVocabulary] memoises until
 * `invalidate()`, so a fresh download is picked up by invalidating, not by
 * rebuilding the container.
 *
 * Absent or unreadable => empty definitions, so the bundled asset stays the
 * vocabulary and a bad download is inert rather than fatal.
 */
class FileVocabularySource(
    private val file: File,
) : TagVocabularySource {
    override val id: String = "downloaded-taxonomy"

    override fun definitions(): List<TagDefinition> =
        if (!file.isFile) {
            emptyList()
        } else {
            runCatching { TaxonomyJson.parse(file.readText()).source().definitions() }
                .getOrDefault(emptyList())
        }
}
