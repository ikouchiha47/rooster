package com.personalos.app.core.mention

/**
 * One spotted entity, before it becomes a row.
 *
 * Confidence is deliberately coarse and fully deterministic — it depends only
 * on where the surface came from, never on the surrounding text:
 *  - a party alias is an explicit proper name: [PARTY_CONFIDENCE];
 *  - a place matched on its display or ASCII name: [CANONICAL_PLACE_CONFIDENCE];
 *  - a place matched only through an alternate name: [ALTERNATE_PLACE_CONFIDENCE].
 */
data class MentionHit(
    val kind: String,
    val surface: String,
    val entityId: String?,
    val confidence: Float,
)

/**
 * Finds places and parties in text. Pure: the [PlaceIndex] and [PartyLexicon]
 * are injected, so this never touches storage — the data layer loads, this
 * only matches (layers stay one-way).
 *
 * Overlapping spans are arbitrated longest-match-wins per position across both
 * kinds, and repeats of the same (kind, surface) collapse to one hit, matching
 * the `mentions` primary key.
 */
class MentionExtractor(
    private val placeIndex: PlaceIndex,
    private val partyLexicon: PartyLexicon = PartyLexicon(BundledPartySource),
) {
    fun extract(text: String): List<MentionHit> {
        if (text.isBlank()) return emptyList()

        val spans = ArrayList<Span>()
        for (hit in placeIndex.find(text)) {
            spans +=
                Span(
                    hit.start,
                    hit.end,
                    MentionHit(
                        MentionKind.PLACE,
                        hit.surface,
                        hit.entityId,
                        if (hit.canonical) CANONICAL_PLACE_CONFIDENCE else ALTERNATE_PLACE_CONFIDENCE,
                    ),
                )
        }
        for (hit in partyLexicon.find(text)) {
            spans +=
                Span(
                    hit.start,
                    hit.end,
                    MentionHit(MentionKind.PARTY, hit.surface, hit.entry.slug, PARTY_CONFIDENCE),
                )
        }
        if (spans.isEmpty()) return emptyList()

        // Earliest start first; at the same start the longest span wins, so a
        // greedy left-to-right pass never lets a shorter match shadow a longer
        // one ("Communist Party of India (Marxist)" beats "Communist Party").
        spans.sortWith(compareBy({ it.start }, { -(it.end - it.start) }))

        val accepted = ArrayList<MentionHit>()
        val seen = LinkedHashSet<Pair<String, String>>()
        var occupiedUntil = -1
        for (span in spans) {
            if (span.start < occupiedUntil) continue
            occupiedUntil = span.end
            if (seen.add(span.hit.kind to span.hit.surface)) {
                accepted += span.hit
            }
        }
        return accepted
    }

    private data class Span(
        val start: Int,
        val end: Int,
        val hit: MentionHit,
    )

    companion object {
        const val PARTY_CONFIDENCE = 0.9f
        const val CANONICAL_PLACE_CONFIDENCE = 0.85f
        const val ALTERNATE_PLACE_CONFIDENCE = 0.7f
    }
}
