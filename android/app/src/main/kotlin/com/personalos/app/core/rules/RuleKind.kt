package com.personalos.app.core.rules

/**
 * Portable rules unit (ADR 0002): the v1 rule kinds and their spec shapes.
 *
 * This package is the portable evaluation core: pure Kotlin, zero `android.*`
 * imports, so a future Workers/R2 reimplementation can lift it unchanged. Keep
 * it that way — anything needing Android (Room, Log, WorkManager) lives in
 * `data/`, never here.
 *
 * The v1 closed set of rule kinds. `kind` is validated at write (see
 * [RuleSpecs]), not a CHECK constraint or an enum table: adding a third kind
 * later is a data + adapter change, never a schema migration.
 */
enum class RuleKind(
    /** The string stored in `rules.kind`. */
    val serialName: String,
) {
    RSS("rss"),
    SEARCH("search"),
    ;

    companion object {
        /** Null when the stored kind is not a v1 kind — callers reject, never default. */
        fun from(value: String): RuleKind? = entries.firstOrNull { it.serialName == value }
    }
}
