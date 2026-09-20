package com.personalos.app.data.adapters

import com.personalos.app.core.sources.SourceKeys
import com.personalos.app.core.sources.SourceSpec
import com.personalos.app.core.sources.SourceSpecs
import com.personalos.app.data.SourceEntity

/**
 * ADR 0005 T7: one adapter per source kind. A new instance is a `sources` row;
 * a new kind is an adapter plus seed rows — never a branch in the evaluator,
 * catalog or form. `identity` is the one mapping from `sources.id` to
 * `events.source` (REQ-ING-14); rules store the identity, never the row id.
 *
 * Fetch frequency is not declared here. It belongs to the fetch itself, so an
 * adapter that reads through [CachedBody] is throttled by the same window the
 * provider uses — one mechanism, not a second one in the dispatcher.
 */
interface KindAdapter {
    val kindId: String

    fun parseSpec(json: String): SourceSpec = SourceSpecs.parse(kindId, json)

    fun identity(source: SourceEntity): String = SourceKeys.sourceFor(source.id, parseSpec(source.specJson))

    suspend fun ingest(
        source: SourceEntity,
        now: Long,
    ): Int
}

/** Unbound kinds skip ingest without throwing (REQ-ING-05). */
fun adaptersByKind(adapters: List<KindAdapter>): Map<String, KindAdapter> = adapters.associateBy { it.kindId }
