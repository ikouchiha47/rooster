package com.personalos.mlsdk

/**
 * The one place a **reference** becomes an artifact.
 *
 * A reference is `name` or `scope:name` — `"embeddinggemma"`, or
 * `"personalos:embeddinggemma"` for the copy one app retrained. The scope is
 * already the artifact's directory's first segment, so a reference is the
 * directory read backwards, and there is nothing new to keep in step.
 *
 * **Unprefixed means [Scope.GLOBAL], and only that.** A miss returns null and
 * never falls back to an app's private artifact: adding
 * `personalos:embeddinggemma` to a device must not silently change what
 * `artifact("embeddinggemma")` means for everybody else. Asking for an app's
 * artifact is explicit, or it does not happen.
 */
class ArtifactRouter(
    private val artifacts: List<Artifact>,
) {
    private val byRef: Map<String, Artifact> = artifacts.associateBy { it.ref }

    /** The artifact [ref] names — `"name"` (global) or `"scope:name"`, or null. */
    fun artifact(ref: String): Artifact? = byRef[normalize(ref)]

    /**
     * The **embedder** [ref] names, or null — null also when the reference resolves
     * to an artifact of another type, so a caller cannot be handed a dataset and
     * told it is their embedder.
     */
    fun embedder(ref: String): Artifact? = typed(ref, ArtifactType.EMBEDDER)

    /** The **dataset** [ref] names, or null. */
    fun dataset(ref: String): Artifact? = typed(ref, ArtifactType.DATASET)

    /** Every artifact of one type — how a consumer asks for "all embedders". */
    fun ofType(type: ArtifactType): List<Artifact> = artifacts.filter { it.type == type }

    /** Every registered artifact — what a manager app lists. */
    fun all(): List<Artifact> = artifacts

    fun names(): List<String> = artifacts.map { it.name }

    fun refs(): List<String> = artifacts.map { it.ref }

    fun isEmpty(): Boolean = artifacts.isEmpty()

    /** The scopes present, e.g. `[global, personalos]` — who owns what here. */
    fun scopes(): List<String> = artifacts.map { it.scope() }.distinct().sorted()

    /** The artifacts of one scope, for a manager app to group by owner. */
    fun inScope(scope: String): List<Artifact> = artifacts.filter { it.scope() == scope }

    /** The types present, for a manager app to group by. */
    fun types(): List<ArtifactType> = artifacts.map { it.type }.distinct()

    private fun typed(
        ref: String,
        type: ArtifactType,
    ): Artifact? = artifact(ref)?.takeIf { it.type == type }

    companion object {
        fun of(vararg artifacts: Artifact): ArtifactRouter = ArtifactRouter(artifacts.toList())

        fun of(artifacts: Collection<Artifact>): ArtifactRouter = ArtifactRouter(artifacts.toList())

        /**
         * Groups a **flat** list of file specs into artifacts by name, each in the
         * [Scope.GLOBAL] directory — where a flat file was expected to be all
         * along.
         *
         * This is the bridge for data written before an artifact owned a scope and
         * a directory: an old `manifest.json`, or a caller that still declares
         * files one by one. Nothing needs migrating.
         */
        fun from(files: Collection<ArtifactFile>): ArtifactRouter =
            ArtifactRouter(
                files
                    .groupBy { it.name }
                    .map { (name, group) ->
                        Artifact.of(name = name, version = group.maxOf { it.version }, files = group)
                    }.sortedBy { it.name },
            )

        /** `"name"` means the shared scope; only a real prefix may select another. */
        private fun normalize(ref: String): String = if (ref.contains(':')) ref else "${Scope.GLOBAL.slug}:$ref"
    }
}
