package com.personalos.app.core.sources

/**
 * Portable sources unit (ADR 0003): the v1 source kinds and their spec shapes.
 *
 * This package is the portable core: pure Kotlin, zero `android.*`
 * imports, so a future Workers/R2 reimplementation can lift it unchanged. Keep
 * it that way — anything needing Android (Room, Log, WorkManager) lives in
 * `data/`, never here.
 *
 * The v1 closed set of source kinds. `kind` is validated at write (see
 * [SourceSpecs]), not a CHECK constraint or an enum table: adding a third kind
 * later is a data + adapter change, never a schema migration.
 */
enum class SourceKind(
    /** The string stored in `sources.kind`. */
    val serialName: String,
) {
    RSS("rss"),
    SEARCH("search"),
    ;

    companion object {
        /** Null when the stored kind is not a v1 kind — callers reject, never default. */
        fun from(value: String): SourceKind? = entries.firstOrNull { it.serialName == value }
    }
}
