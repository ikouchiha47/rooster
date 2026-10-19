package com.personalos.mlsdk

/**
 * What a file *is* within a model, so a runtime asks for a role instead of naming
 * a file.
 *
 * The SDK has no idea what an embedder is, or that ONNX needs its weights beside
 * its graph — that is the consumer's business. What it can carry is the *shape*
 * every multi-file model has: one main artifact, maybe a supporting data file,
 * maybe a tokenizer. Naming those lets a consumer say "give me the graph" without
 * a file-name constant that duplicates what [ArtifactFile.fileName] already owns.
 */
enum class FileRole(
    val slug: String,
) {
    /** The artifact a runtime loads: the `.onnx`, the `.tflite`. */
    GRAPH("graph"),

    /** A file the graph resolves itself, by relative path — ONNX external data. */
    WEIGHTS("weights"),

    /** A tokenizer, vocabulary or config the runtime needs. */
    TOKENIZER("tokenizer"),

    /** Data a model ships and its consumer reads (not a runtime load). */
    DATA("data"),

    /** Anything unclassified, and the default — a single-file model needs no role. */
    OTHER("other"),
    ;

    companion object {
        fun from(slug: String): FileRole = entries.firstOrNull { it.slug == slug } ?: OTHER
    }
}

/**
 * Everything needed to locate, verify and fetch one model file — pure data, so a
 * spec can be checked in, compared and unit-tested with no device.
 *
 * [sizeBytes] and [sha256] are the integrity contract: a file that does not match
 * **both** is not the model, however it got onto the phone. That is what makes a
 * shared folder safe to trust — any app can validate another's copy.
 *
 * [fileName] is the **single owner** of a file's name. Nothing else should restate
 * it; a consumer that needs a particular file asks for its [role].
 */
data class ArtifactFile(
    /** Stable group id, e.g. `"embeddinggemma"`. */
    val name: String,
    /** Bumped when the file changes; carried in the file name and the manifest. */
    val version: Int,
    /** The file's name on disk. */
    val fileName: String,
    /** Where to fetch it when it is absent — usually a release asset. */
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Which part of the model this file is. */
    val role: FileRole = FileRole.OTHER,
    /** Optional metadata the consumer may use (embedding dimension, seq length). */
    val dim: Int? = null,
)
