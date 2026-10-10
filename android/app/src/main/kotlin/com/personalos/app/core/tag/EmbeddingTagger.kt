package com.personalos.app.core.tag

import kotlin.math.sqrt

/**
 * Rung 1 of the ladder: a multilingual sentence embedder, **zero-shot over tag
 * descriptions**.
 *
 * It does not classify into 13 labels; it asks a few group-gated questions of one
 * score set, using [selectTags]: markers are never inferred, subjects are declared
 * or inferred but never read from advertising copy, natures are inferred and
 * capped. The vocabulary lives in [TagVocabularySource] — a new subject is a new
 * sentence, not a code change.
 *
 * The embedder is **injected** as two functions (`suspend (String) -> FloatArray?`
 * and its batch form), so the tagger — thresholds, grouping, gating — is
 * unit-testable with a fake, and the on-device TFLite/ONNX model is just one
 * implementation. The batch form is what lets a batched runtime embed a whole
 * page of items in one run; [embedMany]'s default loops when it cannot. When the
 * model is absent the app runs [HeuristicTagger] instead (see the cascade), never
 * a half-loaded model.
 */
class EmbeddingTagger(
    private val embed: suspend (String) -> FloatArray?,
    private val descriptions: () -> Map<String, String> = TagVocabulary(listOf(BundledVocabulary))::descriptions,
    private val embedMany: suspend (List<String>) -> List<FloatArray?> = { texts -> texts.map { embed(it) } },
    private val schema: TagSchema = TagSchema.BUNDLED,
    override val version: Int = TagVersions.EMBEDDING,
) : Tagger {
    override val id: String = "${TaggerKind.ML.name.lowercase()}-v$version"

    override val kind: TaggerKind = TaggerKind.ML

    /** Tag vectors, computed once: descriptions are fixed for the process. */
    @Volatile
    private var tagVectors: Map<String, FloatArray>? = null

    /**
     * The mean of the tag vectors. Sentence embeddings are **anisotropic** — every
     * vector shares a large common component, so the raw cosine of *anything* against
     * any tag is high (measured: tags mutually ~0.63, an unrelated item 0.4–0.55). Subtracting
     * this centroid from both sides removes the shared part, so unrelated pairs fall
     * to ~0 and a real match stands out. This is the "all-but-the-mean" correction.
     */
    @Volatile
    private var centroid: FloatArray = FloatArray(0)

    override suspend fun tag(input: TagInput): TagResult = tagAll(listOf(input)).first()

    /**
     * Tags a batch in one embedding pass where the runtime can ([embedMany]): a
     * null embedding is "no opinion" — every cosine is 0, so only declared tags
     * survive, never a wrong tag from a failed embed.
     */
    override suspend fun tagAll(inputs: List<TagInput>): List<TagResult> {
        if (inputs.isEmpty()) return emptyList()
        val vectors =
            tagVectors ?: buildVectors().also {
                tagVectors = it
                centroid = meanOf(it.values)
            }
        val center = centroid
        val embedded =
            runCatching { embedMany(inputs.map { PROMPT + it.text }) }
                .getOrElse { List(inputs.size) { null } }

        return inputs.mapIndexed { index, input ->
            val item = embedded.getOrNull(index) ?: FloatArray(0)
            // Center both sides, so the shared component does not make everything match.
            val scores = vectors.mapValues { (_, vector) -> cosine(centered(item, center), centered(vector, center)) }

            val fromAdvert = input.source == Transport.SMS && isDltPromo(input.sender)
            val selection = selectTags(scores, input.declaredTags, fromAdvert, schema)

            // `unsure` is the gate a later LLM rung will use; nothing consumes it yet.
            TagResult(tags = selection.tags, confidence = selection.confidence, taggerId = id)
        }
    }

    /** Elementwise mean of the (same-length) vectors; empty when there are none. */
    private fun meanOf(vectors: Collection<FloatArray>): FloatArray {
        val present = vectors.filter { it.isNotEmpty() }
        if (present.isEmpty()) return FloatArray(0)
        val dim = present.first().size
        val sum = FloatArray(dim)
        for (vector in present) {
            for (i in 0 until minOf(dim, vector.size)) sum[i] += vector[i]
        }
        for (i in 0 until dim) sum[i] /= present.size
        return sum
    }

    /** [v] minus the centroid [c]; unchanged when either is empty (no correction to make). */
    private fun centered(
        v: FloatArray,
        c: FloatArray,
    ): FloatArray {
        if (v.isEmpty() || c.isEmpty()) return v
        return FloatArray(v.size) { v[it] - c.getOrElse(it) { 0f } }
    }

    private suspend fun buildVectors(): Map<String, FloatArray> {
        val entries = descriptions().entries.toList()
        val vectors =
            runCatching { embedMany(entries.map { PROMPT + it.value }) }
                .getOrElse { List(entries.size) { null } }
        return entries.mapIndexed { index, entry -> entry.key to (vectors.getOrNull(index) ?: FloatArray(0)) }.toMap()
    }

    /** DLT promotional headers end in `-P`; the suffix beats any wording. */
    private fun isDltPromo(sender: String?): Boolean = sender?.trim()?.endsWith("-P", ignoreCase = true) == true

    private companion object {
        /**
         * EmbeddingGemma's **classification** prompt. The model's own search prompt
         * scores far worse for tagging (0.44 vs 0.62 on a bank-strike SMS), so the
         * prompt is part of this tagger, not of the embedder.
         */
        const val PROMPT = "task: classification | query: "
    }
}

/** Cosine similarity; 0 when either vector is empty, so a missing embedding is "no opinion". */
internal fun cosine(
    a: FloatArray,
    b: FloatArray,
): Float {
    if (a.isEmpty() || b.isEmpty()) return 0f
    var dot = 0f
    var na = 0f
    var nb = 0f
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    val norm = sqrt(na) * sqrt(nb)
    return if (norm == 0f) 0f else dot / norm
}
