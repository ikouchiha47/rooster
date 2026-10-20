package com.personalos.app.core.tag

/**
 * Rung: **nearest labelled examples**. Where [EmbeddingTagger] compares an item to
 * fixed tag *descriptions*, this compares it to the embeddings of *real, labelled
 * messages* and votes their tags — which is measurably better on real data (a
 * strike SMS sits next to another strike SMS, where the word "strike" scored only
 * 0.14 against the incident description).
 *
 * Composed of three injected parts (Dependency Inversion): an embedder, an
 * [ExampleSource] (bundled seed ∪ corrections), and a pure [KnnClassifier]. The
 * example pool is embedded once and cached, so a run costs one embed per item.
 */
class KnnTagger(
    private val embedMany: suspend (List<String>) -> List<FloatArray?>,
    private val examples: ExampleSource,
    private val classifier: KnnClassifier = KnnClassifier(),
    override val version: Int = TagVersions.KNN,
) : Tagger {
    override val id: String = "${TaggerKind.ML.name.lowercase()}-knn-v$version"

    override val kind: TaggerKind = TaggerKind.ML

    /**
     * The embedded pool plus the fingerprint of the example set it came from.
     *
     * A downloaded dataset can change under a running tagger, so the pool is not
     * built once for the process: each batch loads the examples, hashes them, and
     * only re-embeds when the hash differs. The swap is a compare-and-set, so two
     * concurrent batches cannot install a pool built from different data.
     */
    private data class Pool(
        val fingerprint: Int,
        val examples: List<LabelledExample>,
    )

    private val pool =
        java.util.concurrent.atomic
            .AtomicReference<Pool?>(null)

    override suspend fun tag(input: TagInput): TagResult = tagAll(listOf(input)).first()

    override suspend fun tagAll(inputs: List<TagInput>): List<TagResult> {
        if (inputs.isEmpty()) return emptyList()
        val labelled = poolFor(examples.load())
        val embedded =
            runCatching { embedMany(inputs.map { PROMPT + it.text }) }
                .getOrElse { List(inputs.size) { null } }

        return inputs.mapIndexed { index, input ->
            val vector = embedded.getOrNull(index)
            // A null embedding is "no opinion": only declared tags survive.
            val inferred = if (vector == null) emptySet() else classifier.classify(vector, labelled)
            TagResult(
                tags = input.declaredTags + inferred,
                confidence = if (inferred.isEmpty()) NO_CONFIDENCE else WORD_CONFIDENCE,
                taggerId = id,
                vector = vector,
            )
        }
    }

    private suspend fun poolFor(items: List<ExampleText>): List<LabelledExample> {
        val fingerprint = items.hashCode()
        val current = pool.get()
        if (current?.fingerprint == fingerprint) return current.examples
        if (items.isEmpty()) {
            pool.compareAndSet(current, Pool(fingerprint, emptyList()))
            return pool.get()?.examples ?: emptyList()
        }
        val built = buildPool(items)
        pool.compareAndSet(current, Pool(fingerprint, built))
        return pool.get()?.examples ?: built
    }

    private suspend fun buildPool(items: List<ExampleText>): List<LabelledExample> {
        val vectors =
            runCatching { embedMany(items.map { PROMPT + it.text }) }
                .getOrElse { List(items.size) { null } }
        return items.mapIndexedNotNull { index, example ->
            vectors.getOrNull(index)?.let { LabelledExample(it, example.tags) }
        }
    }

    private companion object {
        const val PROMPT = EmbeddingPrompt.QUERY
        const val WORD_CONFIDENCE = 0.7f
        const val NO_CONFIDENCE = 0.2f
    }
}
