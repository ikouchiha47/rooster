package com.personalos.app.core.tag

/**
 * Which rung **owns** each tag — the split that makes "heuristic + embedder" a
 * combination by *domain*, not by confidence.
 *
 * A confidence cascade would keep the bug: the lexicon is **confidently** wrong
 * (`strike` matched at 0.7 → `travel`). So the two rungs do not vote; each is the
 * authority for the tags it is good at:
 *
 *  - **Lexicon** — curated, non-derivable names. A list knows "Onam"/"MotoGP"
 *    exactly; a description does not reliably fire on a proper noun.
 *  - **Embedding** — contextual subjects and natures, where language beats a
 *    keyword list (a bank *strike* is `incident`, not `travel`) and other scripts
 *    are handled at all.
 *
 * Explicit, like [TagGroups], so adding a tag without deciding its owner fails a
 * partition test rather than defaulting silently.
 */
object TagOwnership {
    /** Curated, non-derivable tags: the lexicon is the authority. */
    val LEXICON: Set<String> =
        setOf(
            Tags.FESTIVAL,
            Tags.GAMES,
            Tags.PAPER,
            Tags.OFFICIAL,
        )

    /** Contextual tags: the embedder is the authority, and may correct the lexicon. */
    val EMBEDDING: Set<String> = (TagGroups.SUBJECTS + TagGroups.NATURES) - LEXICON
}
