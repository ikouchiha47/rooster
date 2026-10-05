package com.personalos.app.core.tag.taxonomy

import com.personalos.app.core.tag.TagDefinition
import com.personalos.app.core.tag.TagVocabularySource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One taxonomy leaf: the tag id, its group, its owner, its description and its lexical terms. */
@Serializable
data class TagLeaf(
    val tag: String,
    val group: String = "",
    val owner: String = "",
    val description: String = "",
    val terms: String = "",
)

/** A derived tag (L3): a relation over facts, not an inferred label. */
@Serializable
data class Derivation(
    val tag: String,
    val from: List<String> = emptyList(),
    @SerialName("when") val whenClause: String = "",
    val note: String = "",
)

/** The versioned tag vocabulary as data — bundled as an asset and downloadable like a model. */
@Serializable
data class Taxonomy(
    val id: String,
    val version: Int,
    val tags: List<TagLeaf> = emptyList(),
    val markers: List<TagLeaf> = emptyList(),
    val derivations: List<Derivation> = emptyList(),
)

object TaxonomyJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun parse(text: String): Taxonomy = json.decodeFromString(text)
}

/**
 * The vocabulary this taxonomy supplies to the taggers. Markers are **excluded**:
 * `news` is structural (declared by the source), never inferred, so it must not
 * reach the embedding rung's description set.
 */
fun Taxonomy.source(): TagVocabularySource =
    object : TagVocabularySource {
        override val id: String = "taxonomy:${this@source.id}:v${this@source.version}"

        override fun definitions(): List<TagDefinition> = this@source.tags.map { TagDefinition(it.tag, it.terms, it.description) }
    }

/** [overlay] wins per leaf id; leaves only it has are added. Markers and derivations merge the same way. */
fun mergeTaxonomies(
    base: Taxonomy,
    overlay: Taxonomy,
): Taxonomy {
    val tags =
        linkedMapOf<String, TagLeaf>().apply {
            base.tags.forEach { put(it.tag, it) }
            overlay.tags.forEach { put(it.tag, it) }
        }
    val markers =
        linkedMapOf<String, TagLeaf>().apply {
            base.markers.forEach { put(it.tag, it) }
            overlay.markers.forEach { put(it.tag, it) }
        }
    val derivations =
        linkedMapOf<String, Derivation>().apply {
            base.derivations.forEach { put(it.tag, it) }
            overlay.derivations.forEach { put(it.tag, it) }
        }
    return Taxonomy(
        id = overlay.id,
        version = overlay.version,
        tags = tags.values.toList(),
        markers = markers.values.toList(),
        derivations = derivations.values.toList(),
    )
}
