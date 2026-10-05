package com.personalos.app.data.models

/**
 * A sentence embedder: text in, a fixed-length vector out — nothing else.
 *
 * Deliberately small and role-specific (docs/CODE-DESIGN-GUIDELINES.md §6): it does
 * **no** tagging, no prompting, no thresholds. The task prompt belongs to the
 * tagger that was tuned with it (`EmbeddingTagger`), and the files are handed in
 * already verified by the SDK's `ModelStore` — so a second runtime (TFLite, ONNX,
 * later something else) is a new implementation of this one method, not a branch
 * in a caller. Implementations are substitutable: the same text yields the same
 * vector regardless of which one runs.
 *
 * [embedMany] exists because a batched runtime (ONNX) can embed a chunk in a
 * single model run; a batch-of-one runtime (TFLite) simply inherits the loop.
 */
interface Embedder {
    /** Whether [init] has already succeeded. */
    fun isReady(): Boolean

    /** Loads the model; false when it cannot (the caller then runs the lexicon alone). */
    suspend fun init(): Boolean

    /** The embedding of [text], or null when not loaded or the run failed. */
    suspend fun embed(text: String): FloatArray?

    /** Embeddings of [texts], in order; a null entry means that one failed. */
    suspend fun embedMany(texts: List<String>): List<FloatArray?> = texts.map { embed(it) }
}
