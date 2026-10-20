package com.personalos.app.core.tag

/**
 * Several example sets as one. Identical text is merged, so a downloaded set can
 * add tags to a bundled example instead of duplicating it (two copies would skew a
 * kNN vote by making the same neighbour count twice).
 */
class CombinedExampleSource(
    private val sources: List<ExampleSource>,
) : ExampleSource {
    override suspend fun load(): List<ExampleText> {
        val merged = LinkedHashMap<String, MutableSet<String>>()
        for (source in sources) {
            for (example in source.load()) {
                merged.getOrPut(example.text) { linkedSetOf() } += example.tags
            }
        }
        return merged.map { (text, tags) -> ExampleText(text, tags) }
    }
}
