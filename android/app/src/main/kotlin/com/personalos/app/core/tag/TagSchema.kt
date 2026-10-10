package com.personalos.app.core.tag

import com.personalos.app.core.tag.taxonomy.Taxonomy

/**
 * The tag *grouping* and *ownership* as data, derived from a [Taxonomy] — the one
 * owner of "which group a tag is in" and "which rung produces it".
 *
 * Previously these were code constants (`TagGroups`, `TagOwnership`). Derived from
 * the taxonomy, a new tag's group/owner is a **data** edit, not code. The taggers
 * take a schema, so a downloaded taxonomy changes their behaviour with no release.
 *
 * Owners:
 *  - **lexicon** — curated terms (`festival`, `games`); the heuristic is the authority.
 *  - **embedding** — contextual (`finance`, `incident`); the model infers it.
 *  - **declared** — source-provided categories (`news`, `politics`); never inferred.
 */
data class TagSchema(
    val markers: Set<String>,
    val subjects: Set<String>,
    val natures: Set<String>,
    val lexiconOwned: Set<String>,
    val declared: Set<String>,
) {
    /** Contextual tags the model owns: subjects+natures, minus lexicon and declared. */
    val embeddingOwned: Set<String> get() = (subjects + natures) - lexiconOwned - declared

    companion object {
        /** Every leaf grouped and owned; categories declared. Throws on a malformed taxonomy. */
        fun of(taxonomy: Taxonomy): TagSchema {
            val subjects =
                taxonomy.tags
                    .filter { it.group == "subject" }
                    .map { it.tag }
                    .toSet()
            val natures =
                taxonomy.tags
                    .filter { it.group == "nature" }
                    .map { it.tag }
                    .toSet()
            val ungrouped = taxonomy.tags.map { it.tag }.toSet() - subjects - natures
            require(ungrouped.isEmpty()) { "taxonomy leaves missing a group: $ungrouped" }
            return TagSchema(
                markers = taxonomy.markers.map { it.tag }.toSet(),
                subjects = subjects,
                natures = natures,
                lexiconOwned =
                    taxonomy.tags
                        .filter { it.owner == "lexicon" }
                        .map { it.tag }
                        .toSet(),
                declared = taxonomy.categories.map { it.tag }.toSet(),
            )
        }

        /**
         * The bundled default, from the code constants. Used as the fallback when no
         * taxonomy is loaded (tests, and the pre-download state).
         */
        val BUNDLED: TagSchema =
            TagSchema(
                markers = TagGroups.MARKERS,
                subjects = TagGroups.SUBJECTS,
                natures = TagGroups.NATURES,
                lexiconOwned = TagOwnership.LEXICON,
                declared = emptySet(),
            )
    }
}
