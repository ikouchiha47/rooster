package com.personalos.app.data

import com.personalos.app.core.catalog.Catalog
import com.personalos.app.core.catalog.Facet
import com.personalos.app.core.catalog.InMemoryCatalog
import com.personalos.app.core.catalog.IngestGrain
import com.personalos.app.core.catalog.Kind
import com.personalos.app.core.catalog.MeasureKind
import com.personalos.app.core.catalog.ValueType
import com.personalos.app.core.catalog.ValuesFrom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * ADR 0005 T6: the single owner of the catalog fact. Reads the three tables
 * and builds the pure core [Catalog]; the UI collects [observeCatalog] and
 * never parses rows itself. Mapping failures fall back to the bundled
 * [com.personalos.app.core.catalog.CatalogSeeds] so a corrupt row cannot blank
 * the rule builder.
 */
class CatalogStore(
    private val kindDao: KindDao,
    private val facetDao: FacetDao,
    private val kindFacetDao: KindFacetDao,
) {
    fun observeCatalog(): Flow<Catalog> =
        combine(kindDao.observeAll(), facetDao.observeAll()) { kinds, facets ->
            build(kinds, facets, emptyMap())
        }

    suspend fun load(): Catalog = build(kindDao.all(), facetDao.all(), kindFacetDao.all().groupBy({ it.kindId }, { it.facetId }).mapValues { it.value.toSet() })

    private fun build(
        kindRows: List<KindEntity>,
        facetRows: List<FacetEntity>,
        joins: Map<String, Set<String>>,
    ): Catalog {
        val facets =
            facetRows.mapNotNull { row ->
                runCatching {
                    Facet(
                        id = row.id,
                        valueType = ValueType.valueOf(row.valueType.uppercase()),
                        measure = MeasureKind.valueOf(row.measure.uppercase()),
                        ops =
                            row.ops
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet(),
                        valuesFrom =
                            row.valuesFrom
                                ?.split("|")
                                ?.filter { it.isNotEmpty() }
                                ?.toSet()
                                ?.let { ValuesFrom(it) },
                    )
                }.getOrNull()
            }
        val facetIds = facets.map { it.id }.toSet()
        val kinds =
            kindRows.mapNotNull { row ->
                runCatching {
                    val joinIds = joins[row.id]
                    Kind(
                        id = row.id,
                        grain = if (row.mode == "series") IngestGrain.SERIES else IngestGrain.ITEM,
                        topics =
                            row.topics
                                .split("|")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet(),
                        facetIds = joinIds?.intersect(facetIds) ?: facetIdsForTopicsFallback(row),
                        enrichable = row.enrichable,
                    )
                }.getOrNull()
            }
        return runCatching { InMemoryCatalog(kinds = kinds, facets = facets) }
            .getOrElse {
                com.personalos.app.core.catalog.CatalogSeeds
                    .catalog()
            }
    }

    private fun facetIdsForTopicsFallback(row: KindEntity): Set<String> = emptySet()

    companion object {
        /**
         * REQ-CAT-06: never offer a kind with no enabled instance. Pure — the
         * data layer supplies [enabledKinds] from `sources`, the UI reads the
         * gated catalog.
         */
        fun gated(
            catalog: Catalog,
            enabledKinds: Set<String>,
        ): Catalog {
            val kinds = enabledKinds.mapNotNull { catalog.kind(it) }
            val facets = kinds.flatMap { it.facetIds }.toSet().mapNotNull { catalog.facet(it) }
            return InMemoryCatalog(kinds = kinds, facets = facets)
        }
    }
}
