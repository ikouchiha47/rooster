package com.personalos.mlsdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ModelSpecDto(
    val name: String,
    val version: Int,
    val file: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val dim: Int? = null,
)

@Serializable
private data class ModelManifestDto(
    val version: Int = 1,
    val models: List<ModelSpecDto>,
)

/**
 * The shared contract: one `manifest.json` beside the model files, listing what
 * exists and its integrity. Every app reads the same file, so "embeddinggemma
 * v1" means one name, one size and one sha to all of them — which is what makes
 * one app's download trustworthy to the rest.
 *
 * ```json
 * { "version": 1, "models": [
 *   { "name": "embeddinggemma", "version": 1, "file": "mofy_embedder_seq256.tflite",
 *     "url": "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/mofy_embedder_seq256.tflite",
 *     "size": 179131736, "sha256": "…", "dim": 256 } ] }
 * ```
 */
object ModelManifest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parse a manifest, or null when it is unreadable — a bad manifest is not a crash. */
    fun parse(text: String): List<ModelSpec>? =
        runCatching {
            json
                .decodeFromString<ModelManifestDto>(text)
                .models
                .map { ModelSpec(it.name, it.version, it.file, it.url, it.size, it.sha256, it.dim) }
        }.getOrNull()

    /** The manifest text for a set of specs — the writer side, so a hub can publish one. */
    fun write(specs: List<ModelSpec>): String =
        json.encodeToString(
            ModelManifestDto(
                models =
                    specs.map {
                        ModelSpecDto(it.name, it.version, it.fileName, it.url, it.sizeBytes, it.sha256, it.dim)
                    },
            ),
        )
}
