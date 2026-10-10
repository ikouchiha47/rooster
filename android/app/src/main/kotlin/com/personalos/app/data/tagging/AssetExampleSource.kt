package com.personalos.app.data.tagging

import android.content.Context
import com.personalos.app.core.tag.ExampleSource
import com.personalos.app.core.tag.ExampleText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The bundled seed of labelled examples, read from the APK's assets. */
class AssetExampleSource(
    private val context: Context,
    private val assetPath: String = DEFAULT_ASSET,
) : ExampleSource {
    override suspend fun load(): List<ExampleText> =
        runCatching {
            val text = context.assets.open(assetPath).use { it.readBytes().decodeToString() }
            ExamplesJson.parse(text).examples.map { ExampleText(it.text, it.tags.toSet()) }
        }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_ASSET = "tagging/examples.v1.json"
    }
}

@Serializable
internal data class ExamplesFile(
    val id: String,
    val version: Int,
    val examples: List<ExampleEntry> = emptyList(),
)

@Serializable
internal data class ExampleEntry(
    val text: String,
    val tags: List<String> = emptyList(),
)

internal object ExamplesJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun parse(text: String): ExamplesFile = json.decodeFromString(text)
}
