package com.personalos.app.core.tag

/** One labelled example: its embedding and the tag set it carries. */
class LabelledExample(
    val vector: FloatArray,
    val tags: Set<String>,
)

/**
 * k-nearest-neighbour tag vote over labelled examples. **Pure** — no model, no IO —
 * so the decision rule is tested on its own and the on-device example set is just
 * data.
 *
 * Vectors are **centered** (all-but-the-mean) before the cosine: sentence
 * embeddings are anisotropic, so raw cosine makes every example look alike. A tag
 * scores the best similarity among the [k] nearest examples that carry it, and only
 * tags above [minSimilarity] survive — unrelated items get nothing.
 */
class KnnClassifier(
    private val k: Int = DEFAULT_K,
    private val minSimilarity: Float = DEFAULT_MIN_SIMILARITY,
) {
    fun classify(
        query: FloatArray,
        examples: List<LabelledExample>,
    ): Set<String> {
        if (examples.isEmpty() || query.isEmpty()) return emptySet()
        val centroid = centroidOf(examples)
        val centeredQuery = center(query, centroid)
        val nearest =
            examples
                .map { it to cosine(centeredQuery, center(it.vector, centroid)) }
                .sortedByDescending { it.second }
                .take(k)

        val best = HashMap<String, Float>()
        for ((example, similarity) in nearest) {
            for (tag in example.tags) best[tag] = maxOf(best[tag] ?: -1f, similarity)
        }
        return best.filterValues { it >= minSimilarity }.keys
    }

    private fun centroidOf(examples: List<LabelledExample>): FloatArray {
        val present = examples.map { it.vector }.filter { it.isNotEmpty() }
        if (present.isEmpty()) return FloatArray(0)
        val dim = present.first().size
        val sum = FloatArray(dim)
        for (vector in present) {
            for (i in 0 until minOf(dim, vector.size)) sum[i] += vector[i]
        }
        for (i in 0 until dim) sum[i] /= present.size
        return sum
    }

    private fun center(
        vector: FloatArray,
        centroid: FloatArray,
    ): FloatArray {
        if (vector.isEmpty() || centroid.isEmpty()) return vector
        return FloatArray(vector.size) { vector[it] - centroid.getOrElse(it) { 0f } }
    }

    companion object {
        const val DEFAULT_K = 5
        const val DEFAULT_MIN_SIMILARITY = 0.30f
    }
}
