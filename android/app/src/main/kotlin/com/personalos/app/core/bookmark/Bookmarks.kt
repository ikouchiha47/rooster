package com.personalos.app.core.bookmark

/**
 * Saving an item, as one pure rule. Zero `android.*`, so the Saved view and the
 * toggle read the same definition of "saved" and cannot drift.
 *
 * Saved is a **stamp**, not a boolean: the moment it was saved is what orders
 * the Saved view later. That also means `0` is a perfectly valid save — the
 * check is presence, never truthiness.
 */
fun nextBookmark(
    current: Long?,
    now: Long,
): Long? = if (current == null) now else null

/** True when the item carries a saved stamp, including one equal to zero. */
fun isSaved(bookmarkedAt: Long?): Boolean = bookmarkedAt != null
