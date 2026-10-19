package com.personalos.mlsdk

/**
 * What is registered on this device: the artifacts, and the **scopes** that own
 * them.
 *
 * Scopes are part of the registry rather than a library constant, because only the
 * apps know their own names. Registering `personalos:embeddinggemma` is what makes
 * `personalos` a known scope — there is no list to update in here, which is what
 * keeps this module reusable.
 *
 * Pure data with pure operations, so a merge is unit-testable with no filesystem.
 * [Hub.register] is the side that persists it.
 */
data class ArtifactRegistry(
    val scopes: List<String>,
    val artifacts: List<Artifact>,
) {
    fun isEmpty(): Boolean = artifacts.isEmpty()

    /** The artifact [ref] names — `"name"` (global) or `"scope:name"`. */
    fun artifact(ref: String): Artifact? = router().artifact(ref)

    /**
     * This registry with [additions] registered, replacing an artifact of the same
     * `(dir, name)` and unioning scopes.
     *
     * Replacing by **directory**, not by name, is deliberate:
     * `global:embeddinggemma` and `personalos:embeddinggemma` are *different
     * artifacts* and must both survive.
     */
    fun plus(additions: Collection<Artifact>): ArtifactRegistry {
        val replaced = additions.map { it.dir to it.name }.toSet()
        val merged = artifacts.filterNot { (it.dir to it.name) in replaced } + additions
        return of(merged).copy(scopes = (scopes + additions.map { it.scope() }).distinct().sorted())
    }

    /** The artifacts as a router, for reference lookup. */
    fun router(): ArtifactRouter = ArtifactRouter(artifacts)

    companion object {
        val EMPTY = ArtifactRegistry(scopes = emptyList(), artifacts = emptyList())

        /** A registry of exactly [artifacts], with the scopes they imply. */
        fun of(artifacts: Collection<Artifact>): ArtifactRegistry =
            ArtifactRegistry(
                scopes = artifacts.map { it.scope() }.distinct().sorted(),
                artifacts = artifacts.sortedWith(compareBy({ it.dir }, { it.name })),
            )
    }
}
