package com.personalos.app.core.tag

/** One labelled example as data: the raw text and the tag set it carries. */
data class ExampleText(
    val text: String,
    val tags: Set<String>,
)

/**
 * Where a [KnnTagger]'s labelled examples come from. A bundled seed, the user's
 * corrections, or both merged — the tagger depends on this interface, not on where
 * the data lives (Dependency Inversion).
 */
interface ExampleSource {
    suspend fun load(): List<ExampleText>
}
