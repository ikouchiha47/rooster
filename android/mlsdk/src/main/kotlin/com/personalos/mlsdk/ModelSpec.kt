package com.personalos.mlsdk

/**
 * Everything needed to locate, verify and fetch one model file — pure data, so a
 * spec can be checked in, compared and unit-tested with no device.
 *
 * [sizeBytes] and [sha256] are the integrity contract: a file that does not match
 * **both** is not the model, however it got onto the phone. That is what makes a
 * shared folder safe to trust — any app can validate another's copy.
 */
data class ModelSpec(
    /** Stable group id, e.g. `"embeddinggemma"`. */
    val name: String,
    /** Bumped when the file changes; carried in the file name and the manifest. */
    val version: Int,
    /** The file's name on disk, e.g. `"mofy_embedder_seq256.tflite"`. */
    val fileName: String,
    /** Where to fetch it when it is absent — usually a GitHub release asset. */
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Optional metadata the consumer may use (embedding dimension, seq length). */
    val dim: Int? = null,
)
