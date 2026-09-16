package com.personalos.app.core.tag

/**
 * The one owner of the tag *grouping* (docs/ARCHITECTURE.md §10.5): a **subject**
 * is what an item is about, a **nature** is what kind of thing it is, and the
 * **marker** is structural. The tag *names* live in [Tags]; only the grouping
 * lives here.
 *
 * Membership is explicit, not derived by subtraction, so adding a tag to [Tags]
 * without deciding its group fails the partition test in `TagGroupsTest` rather
 * than silently becoming a nature.
 *
 * Note the prose in §10.5 lists `announcement` and `maintenance` as natures, but
 * neither exists as a [Tags] constant. They are deliberately *not* added here —
 * the vocabulary is code, and this file groups what the code has.
 */
object TagGroups {
    /** The structural marker: `news` only says "this is editorial content". */
    val MARKERS: Set<String> = setOf(Tags.NEWS)

    /**
     * Tags that answer "what is this about". A subject is a stronger claim than
     * a nature, so it is the one the tagger refuses to infer from advertising
     * copy (see `HeuristicTagger`).
     */
    val SUBJECTS: Set<String> =
        setOf(
            Tags.FINANCE,
            Tags.TECH,
            Tags.TRAVEL,
            Tags.FESTIVAL,
            Tags.GAMES,
            Tags.WEATHER,
            Tags.PAPER,
        )

    /** Tags that answer "what kind of thing is this". Never a subject, never the marker. */
    val NATURES: Set<String> =
        setOf(
            Tags.EXPENSE,
            Tags.PROMO,
            Tags.INCIDENT,
            Tags.PERSONAL,
            Tags.OFFICIAL,
        )
}
