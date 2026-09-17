package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.catalog.CatalogSeeds
import com.personalos.app.core.catalog.IngestGrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ADR 0005 T6: reconciles `kinds` / `facets` / `kind_facets` with the bundled
 * [CatalogSeeds] on every launch. Add-only by id, like [SourceSeeder]: a row
 * that already exists is never updated, so a later release's new kind reaches
 * an existing install while stored rows survive.
 */
class CatalogSeeder(
    private val kindDao: KindDao,
    private val facetDao: FacetDao,
    private val kindFacetDao: KindFacetDao,
) {
    suspend fun seed(now: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            runCatching {
                val storedKinds = kindDao.all().associateBy { it.id }
                val storedFacets = facetDao.all().associateBy { it.id }
                val storedJoins = kindFacetDao.all().map { it.kindId to it.facetId }.toSet()

                val kinds =
                    CatalogSeeds.kinds().map { kind ->
                        KindEntity(
                            id = kind.id,
                            mode = if (kind.grain == IngestGrain.SERIES) "series" else "item",
                            topics = kind.topics.sorted().joinToString("|"),
                            enrichable = kind.enrichable,
                            seeded = true,
                            createdAt = now,
                        )
                    }
                val facets =
                    CatalogSeeds.facets().map { facet ->
                        FacetEntity(
                            id = facet.id,
                            valueType = facet.valueType.name.lowercase(),
                            measure = facet.measure.name.lowercase(),
                            ops = facet.ops.sorted().joinToString(","),
                            valuesFrom =
                                facet.valuesFrom
                                    ?.values
                                    ?.sorted()
                                    ?.joinToString("|"),
                            seeded = true,
                            createdAt = now,
                        )
                    }
                val joins =
                    CatalogSeeds.kinds().flatMap { kind ->
                        kind.facetIds.map { KindFacetEntity(kindId = kind.id, facetId = it) }
                    }

                // Fail closed before the first write: the in-memory catalog
                // validates unknown ops and conflicting facet rows.
                com.personalos.app.core.catalog.InMemoryCatalog(
                    kinds = CatalogSeeds.kinds(),
                    facets = CatalogSeeds.facets(),
                )

                var added = 0
                val missingKinds = kinds.filter { it.id !in storedKinds }
                val missingFacets = facets.filter { it.id !in storedFacets }
                val missingJoins = joins.filter { (it.kindId to it.facetId) !in storedJoins }
                if (missingKinds.isNotEmpty()) added += kindDao.insertAll(missingKinds).count { it != -1L }
                if (missingFacets.isNotEmpty()) added += facetDao.insertAll(missingFacets).count { it != -1L }
                if (missingJoins.isNotEmpty()) added += kindFacetDao.insertAll(missingJoins).count { it != -1L }
                if (added > 0) Log.i(TAG, "catalog seed: +$added")
                added
            }.onFailure { Log.w(TAG, "catalog seed failed", it) }
                .getOrDefault(0)
        }

    private companion object {
        const val TAG = "Catalog"
    }
}
