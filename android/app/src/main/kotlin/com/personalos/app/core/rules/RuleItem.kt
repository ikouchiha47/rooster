package com.personalos.app.core.rules

/**
 * A mention as the evaluator sees it: a [kind] (`place`, `party`, ...) and the
 * stored [value] (the gazetteer/lexicon surface as stored, not the text's
 * casing).
 */
data class RuleMention(
    val kind: String,
    val value: String,
)

/**
 * One item as the rule evaluator sees it (ADR 0003 §6, plan T3.1): transport-
 * agnostic and storage-agnostic. The data layer builds this from stored facts —
 * `events`, the active tagger's `item_tags`, `mentions` — so the pure core never
 * sees a Room entity and keeps no dependency on the data layer.
 *
 * [fields] carries the kind-specific typed extras (`amount`, `price`, ...)
 * stored in `item_fields` (ADR 0003 §13). The data layer reads them from the
 * store, like tags and mentions; a [Condition.Field] over a name no producer
 * wrote matches nothing rather than guessing.
 */
data class RuleItem(
    /** `events.ulid` — stable identity, never content-derived. */
    val id: String,
    /** `events.source` — the source's identity, not its transport. */
    val sourceId: String,
    val title: String,
    /** Stored body text, which enrichment can grow after ingest (ADR §6). */
    val content: String,
    /** The item's tags as stored: what subject / nature / marker membership reads. */
    val tags: Set<String>,
    val mentions: List<RuleMention>,
    val fields: Map<String, FieldValue> = emptyMap(),
)
