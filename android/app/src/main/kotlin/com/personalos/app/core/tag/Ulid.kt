package com.personalos.app.core.tag

import ulid.ULID

/**
 * One-line indirection so the rest of the app never imports the ULID library
 * directly and the implementation can be swapped without touching call sites.
 *
 * ULIDs are lexicographically sortable by creation time and collision-free
 * offline, so they are safe as a stable identity for export and future merges.
 * See docs/ARCHITECTURE.md §11.1.
 */
object Ulid {
    fun next(): String = ULID.randomULID()
}
