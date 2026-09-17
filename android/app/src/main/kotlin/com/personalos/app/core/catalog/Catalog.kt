package com.personalos.app.core.catalog

/**
 * ADR 0005: the facet catalog — pure Kotlin, zero `android.*`.
 *
 * A facet is one authorable fact (its id, value type, measure kind and allowed
 * ops). A kind is a producer descriptor (its grain, topics, facets and whether
 * its items are enrichable). The UI renders this; it never owns keys or ops.
 */
enum class ValueType {
    NUM,
    STR,
    ENUM,
    FLAG,
}

enum class MeasureKind {
    COUNTER,
    GAUGE,
    HISTOGRAM,
}

enum class IngestGrain {
    ITEM,
    SERIES,
}

data class ValuesFrom(
    val values: Set<String>,
)

data class Facet(
    val id: String,
    val valueType: ValueType,
    val measure: MeasureKind,
    val ops: Set<String>,
    val valuesFrom: ValuesFrom? = null,
)

data class Kind(
    val id: String,
    val grain: IngestGrain,
    val topics: Set<String>,
    val facetIds: Set<String>,
    val enrichable: Boolean,
)

interface Catalog {
    fun kind(id: String): Kind?

    fun facet(id: String): Facet?

    fun facetsForTopics(topics: Set<String>): List<Facet>

    fun kindsForTopic(topic: String): List<Kind>
}

/**
 * In-memory catalog with seed-time validation: unknown ops and conflicting
 * facet rows fail closed with [IllegalArgumentException] (REQ-CAT-04/05).
 */
class InMemoryCatalog(
    kinds: List<Kind>,
    facets: List<Facet>,
) : Catalog {
    private val kindsById: Map<String, Kind> = kinds.associateBy { it.id }
    private val facetsById: Map<String, Facet>

    init {
        val seen = LinkedHashMap<String, Facet>()
        for (facet in facets) {
            val unknown = facet.ops - OpMatrix.knownOps
            require(unknown.isEmpty()) {
                "facet '${facet.id}' lists unknown ops: ${unknown.sorted()}"
            }
            require(facet.ops.isNotEmpty()) {
                "facet '${facet.id}' must declare a non-empty op set"
            }
            val existing = seen[facet.id]
            if (existing != null) {
                require(existing.valueType == facet.valueType && existing.measure == facet.measure) {
                    "facet '${facet.id}' conflicts: $existing vs $facet"
                }
            } else {
                seen[facet.id] = facet
            }
        }
        facetsById = seen
    }

    override fun kind(id: String): Kind? = kindsById[id]

    override fun facet(id: String): Facet? = facetsById[id]

    override fun facetsForTopics(topics: Set<String>): List<Facet> {
        val ids = LinkedHashSet<String>()
        for (kind in kindsById.values) {
            if (kind.topics.intersect(topics).isNotEmpty()) {
                ids.addAll(kind.facetIds)
            }
        }
        return ids.mapNotNull { facetsById[it] }
    }

    override fun kindsForTopic(topic: String): List<Kind> = kindsById.values.filter { topic in it.topics }
}
