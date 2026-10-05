package com.personalos.app.core.tag

/**
 * The current version of each tagger rung, in one place — the one owner.
 *
 * A tagger's version is part of its **identity** (`"cascade-v2"`), it is stored on
 * every tag row, and it keys the retag cursor. So bumping it is what makes a
 * behaviour change re-tag the store. Keeping all three here means that bump is a
 * single edit rather than a hunt for the default in each class (and the literal in
 * each test).
 *
 * Bump the rung whose behaviour changed:
 *  - [HEURISTIC] — the term lists / rules,
 *  - [EMBEDDING] — the description set or the scoring (e.g. centering),
 *  - [CASCADE] — how the rungs are combined.
 */
object TagVersions {
    const val HEURISTIC = 10
    const val EMBEDDING = 2
    const val CASCADE = 2
}
