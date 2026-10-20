package com.personalos.app.data.tagging

import com.personalos.app.core.tag.ExampleSource
import com.personalos.app.core.tag.ExampleText
import java.io.File

/**
 * A downloaded example set, layered over the bundled seed. Read on each call, so
 * [com.personalos.app.core.tag.KnnTagger] can notice a new fingerprint and rebuild
 * its pool without a restart.
 *
 * Absent or unreadable => empty, so a bad download never removes the bundled seed.
 */
class FileExampleSource(
    private val file: File,
) : ExampleSource {
    override suspend fun load(): List<ExampleText> =
        if (!file.isFile) {
            emptyList()
        } else {
            runCatching {
                ExamplesJson.parse(file.readText()).examples.map { ExampleText(it.text, it.tags.toSet()) }
            }.getOrDefault(emptyList())
        }
}
