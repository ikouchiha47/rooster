package com.personalos.app.core.catalog

import com.personalos.app.core.rules.Condition
import com.personalos.app.core.rules.ConditionStringify

/**
 * ADR 0005 T10: catalog-driven authoring — pure. The UI lists [fieldOptions]
 * and [opOptions] from the catalog, compiles [FacetClause]s through
 * [FacetCompiler], and saves the stringified JSON. Stored rules stay the
 * frozen `Condition` language; the form never owns keys or ops.
 */
object DraftCompiler {
    fun fieldOptions(
        catalog: Catalog,
        topic: String,
    ): List<String> = catalog.facetsForTopics(setOf(topic)).map { it.id }

    fun opOptions(
        catalog: Catalog,
        facetId: String,
    ): Set<String> = catalog.facet(facetId)?.ops.orEmpty()

    fun compileAll(
        clauses: List<FacetClause>,
        catalog: Catalog,
    ): String {
        require(clauses.isNotEmpty()) { "draft: needs at least one clause" }
        val conditions = clauses.map { FacetCompiler.toCondition(it, catalog) }
        val root = if (conditions.size == 1) conditions.first() else Condition.All(conditions)
        return ConditionStringify.stringify(root)
    }

    fun compileAny(
        clauses: List<FacetClause>,
        catalog: Catalog,
    ): String {
        require(clauses.isNotEmpty()) { "draft: needs at least one clause" }
        val conditions = clauses.map { FacetCompiler.toCondition(it, catalog) }
        val root = if (conditions.size == 1) conditions.first() else Condition.Any(conditions)
        return ConditionStringify.stringify(root)
    }
}
