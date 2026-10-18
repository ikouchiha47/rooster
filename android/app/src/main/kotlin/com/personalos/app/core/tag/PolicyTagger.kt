package com.personalos.app.core.tag

/**
 * The combination: the lexicon for curated tags, the embedder for contextual
 * ones, neither for markers.
 *
 * It is **not** a confidence cascade. Both rungs run, and the merge is by
 * [TagOwnership], so the embedder is the *only* voice on a contextual tag — the
 * lexicon's `travel` on a bank strike is dropped because the embedder owns
 * `travel`, not because its confidence was lower. Curated tags come from the
 * lexicon (and the embedder if it happens to find them); markers and declared
 * subjects come from the ingest path alone.
 *
 * With the model absent the caller passes the heuristic for both, which makes this
 * the identity — the app keeps tagging, never blocked (the ladder's rule).
 */
class PolicyTagger(
    private val lexicon: Tagger,
    private val embedding: Tagger,
    private val schema: TagSchema = TagSchema.BUNDLED,
) : Tagger {
    override val version: Int = TagVersions.CASCADE

    override val id: String = "${TaggerKind.CASCADE.name.lowercase()}-v$version"

    override val kind: TaggerKind = TaggerKind.CASCADE

    override suspend fun tag(input: TagInput): TagResult = tagAll(listOf(input)).first()

    /**
     * Batches both rungs per item — the lexicon by its own default loop, the
     * embedder in one run when its runtime supports it — then merges positionally.
     */
    override suspend fun tagAll(inputs: List<TagInput>): List<TagResult> {
        if (inputs.isEmpty()) return emptyList()
        val fromLexicon = lexicon.tagAll(inputs)
        val fromEmbedding = embedding.tagAll(inputs)
        return inputs.mapIndexed { index, input ->
            merge(
                input,
                fromLexicon.getOrElse(index) { none },
                fromEmbedding.getOrElse(index) { none },
            )
        }
    }

    private fun merge(
        input: TagInput,
        fromLexicon: TagResult,
        fromEmbedding: TagResult,
    ): TagResult {
        val tags = linkedSetOf<String>()
        // Declared and structural: the ingest path's, never inferred here.
        tags += input.declaredTags
        // Contextual, plus any curated the model found: the embedder's to give.
        tags += fromEmbedding.tags.filterNot { it in schema.markers }
        // Curated: the lexicon's to give.
        tags += fromLexicon.tags.filter { it in schema.lexiconCovers }

        return TagResult(
            tags = tags,
            confidence = maxOf(fromLexicon.confidence, fromEmbedding.confidence),
            taggerId = id,
        )
    }

    private val none = TagResult(tags = emptySet(), confidence = 0f, taggerId = id)
}
