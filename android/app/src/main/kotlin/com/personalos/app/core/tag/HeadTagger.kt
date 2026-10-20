package com.personalos.app.core.tag

import kotlin.math.exp

/** One logistic head over the frozen embedding, with the tags it may emit. */
data class Head(
    val label: String,
    val weights: FloatArray,
    val bias: Float,
    val threshold: Float,
    val tags: Set<String>,
)

/**
 * The trained classifier: one balanced logistic head per coarse class, over the
 * **uncentered, L2-normalised** EmbeddingGemma vector (the geometry the heads were
 * fitted on). A class with no tags (`politics`, `entertainment`, `health`,
 * `education`) is trained but never inferred — those are declared by the source or
 * absent from taxonomy v1. Several heads may map to one tag (`crime` and
 * `incident` both yield `incident`); the best probability wins.
 *
 * Pure, so the decision rule is unit-tested without the model.
 */
class HeadClassifier(
    private val heads: List<Head>,
) {
    fun classify(vector: FloatArray): Map<String, Float> {
        if (vector.isEmpty()) return emptyMap()
        val best = HashMap<String, Float>()
        for (head in heads) {
            if (head.tags.isEmpty() || head.weights.size != vector.size) continue
            var logit = head.bias
            for (i in vector.indices) logit += head.weights[i] * vector[i]
            val probability = 1f / (1f + exp(-logit))
            if (probability < head.threshold) continue
            for (tag in head.tags) best[tag] = maxOf(best[tag] ?: 0f, probability)
        }
        return best
    }
}

/**
 * The ML rung once the trained heads are on disk: EmbeddingGemma for the vector,
 * logistic weights for the label, taxonomy tags for the answer. Same [Tagger]
 * contract as [KnnTagger], so `PolicyTagger` merges it identically and no tile
 * knows which rung is live.
 */
class HeadTagger(
    private val embedMany: suspend (List<String>) -> List<FloatArray?>,
    val heads: HeadSet,
    private val classifier: HeadClassifier = HeadClassifier(heads.heads),
    override val version: Int = TagVersions.HEADS,
) : Tagger {
    override val id: String = "${TaggerKind.ML.name.lowercase()}-heads-v$version"

    override val kind: TaggerKind = TaggerKind.ML

    override suspend fun tag(input: TagInput): TagResult = tagAll(listOf(input)).first()

    override suspend fun tagAll(inputs: List<TagInput>): List<TagResult> {
        if (inputs.isEmpty()) return emptyList()
        val embedded =
            runCatching { embedMany(inputs.map { heads.prompt + it.text }) }
                .getOrElse { List(inputs.size) { null } }

        return inputs.mapIndexed { index, input ->
            val vector = embedded.getOrNull(index)
            val scores = if (vector == null) emptyMap() else classifier.classify(vector)
            TagResult(
                tags = input.declaredTags + scores.keys,
                confidence = scores.values.maxOrNull() ?: NO_CONFIDENCE,
                taggerId = id,
                vector = vector,
            )
        }
    }

    private companion object {
        const val NO_CONFIDENCE = 0.2f
    }
}

/** The heads file as the runtime needs it: model id, prompt, and the classes. */
data class HeadSet(
    val id: String,
    val version: Int,
    val dim: Int,
    val prompt: String,
    val heads: List<Head>,
)
