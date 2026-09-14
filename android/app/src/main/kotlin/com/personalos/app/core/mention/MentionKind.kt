package com.personalos.app.core.mention

/**
 * Mention kinds. Stored as free TEXT on the `mentions` row (never an enum
 * table), so recognising a new kind of entity later is a new extractor, not a
 * migration.
 */
object MentionKind {
    const val PLACE = "place"
    const val PARTY = "party"
}
