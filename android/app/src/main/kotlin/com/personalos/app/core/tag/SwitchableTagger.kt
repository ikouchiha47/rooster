package com.personalos.app.core.tag

/**
 * Holds the active tagger behind a stable reference, so the *implementation* can
 * change at runtime without re-wiring writers.
 *
 * It starts as the heuristic and is [use]d to swap in the policy tagger once the
 * model has loaded — because the model is downloaded, not bundled, the app is live
 * before the embedder exists. Ingest therefore never waits on a model: it tags
 * with whatever is active now, and a version bump re-tags the store afterwards
 * (`Retagger`), exactly as a tagger change always has.
 */
class SwitchableTagger(
    initial: Tagger,
) : Tagger {
    @Volatile
    private var current: Tagger = initial

    /** Swap the active implementation. Safe to call from any thread. */
    fun use(tagger: Tagger) {
        current = tagger
    }

    override val id: String get() = current.id

    override val kind: TaggerKind get() = current.kind

    override val version: Int get() = current.version

    override suspend fun tag(input: TagInput): TagResult = current.tag(input)

    override suspend fun tagAll(inputs: List<TagInput>): List<TagResult> = current.tagAll(inputs)
}
