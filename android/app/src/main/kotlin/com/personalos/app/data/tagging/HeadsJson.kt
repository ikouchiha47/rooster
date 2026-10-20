package com.personalos.app.data.tagging

import com.personalos.app.core.tag.Head
import com.personalos.app.core.tag.HeadSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The trained heads, read from the downloaded classifier artifact. */
class HeadsJson(
    private val file: java.io.File,
) {
    fun load(): HeadSet? =
        if (!file.isFile) {
            null
        } else {
            runCatching { parse(file.readText()) }.getOrNull()
        }

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        fun parse(text: String): HeadSet {
            val doc = json.decodeFromString<HeadsFile>(text)
            return HeadSet(
                id = doc.id,
                version = doc.version,
                dim = doc.dim,
                prompt = doc.prompt,
                heads =
                    doc.classes.map { entry ->
                        Head(
                            label = entry.label,
                            weights = entry.weights.toFloatArray(),
                            bias = entry.bias,
                            threshold = entry.threshold,
                            tags = entry.tags.toSet(),
                        )
                    },
            )
        }
    }
}

@Serializable
internal data class HeadsFile(
    val id: String,
    val version: Int,
    val dim: Int,
    val prompt: String,
    val classes: List<HeadEntry> = emptyList(),
)

@Serializable
internal data class HeadEntry(
    val label: String,
    val threshold: Float = 0.5f,
    val bias: Float = 0f,
    val weights: List<Float> = emptyList(),
    val tags: List<String> = emptyList(),
)
