package com.personalos.app.core.tag

/**
 * Which text represents an item for embedding, and whether that text exists yet.
 *
 * The body (a feed summary, or an enriched article summary) is the reliable,
 * factual signal; the headline is **not**, because a headline can be sarcastic or
 * clickbait. So the body is used whenever there is one, and the headline only as a
 * last resort - never as a stand-in for a body that is merely late.
 */
enum class BodyState {
    /** A usable body exists; embed it. */
    PRESENT,

    /** No usable body, and none is coming: the article cannot be fetched, or the
     * fetch was attempted and produced nothing. The headline is all there is. */
    ABSENT_FINAL,

    /** No usable body yet, but one may still arrive (enrichable, never attempted).
     * Do not tag on the headline: defer until the body lands or the attempt fails. */
    PENDING,
}

/**
 * The single owner of the "which text do we embed" rule.
 *
 * The `≥ [USEFULLY_EMPTY_CHARS]` threshold is deliberately the **same** notion the
 * enrichment candidates use (`Sql.ENRICHMENT_CANDIDATES`'s `LENGTH(TRIM(content)) <
 * 40`): a body shorter than that is "usefully empty", so the two subsystems agree on
 * what "has no body" means and an item is never simultaneously enrichable and
 * already-bodied.
 */
object EmbedText {
    /** Below this, a body is treated as absent - the enrichment candidates' own floor. */
    const val USEFULLY_EMPTY_CHARS = 40

    /**
     * The character budget above which a body is compressed. Well above the p99 of
     * a real item (~420 chars), so compression is insurance against a pathological
     * feed, not a routine cut.
     */
    const val DEFAULT_BUDGET_CHARS = 1_200

    /**
     * Classifies an item from the three facts that decide it: its body, whether it
     * can be enriched at all (`url`), and whether enrichment has been attempted
     * (`enrichedAt`: `null` never, `0` failed, `>0` succeeded or produced nothing).
     */
    fun stateOf(
        content: String?,
        url: String?,
        enrichedAt: Long?,
    ): BodyState {
        val body = content?.trim().orEmpty()
        return when {
            body.length >= USEFULLY_EMPTY_CHARS -> BodyState.PRESENT
            // Attempted (success with a tiny result, or a recorded failure): terminal.
            enrichedAt != null -> BodyState.ABSENT_FINAL
            // Never enrichable (no link): terminal.
            url == null -> BodyState.ABSENT_FINAL
            else -> BodyState.PENDING
        }
    }

    /**
     * The text to embed, or `null` to defer. [state] comes from [stateOf]; [title]
     * is the headline, used only in [BodyState.ABSENT_FINAL] when there is not even
     * a short body.
     */
    fun of(
        title: String,
        content: String?,
        state: BodyState,
        budgetChars: Int = DEFAULT_BUDGET_CHARS,
    ): String? =
        when (state) {
            BodyState.PRESENT -> SalientSentences.compress(content.orEmpty(), budgetChars)
            // A short body (< the floor) is still real text; only fall to the headline
            // when there is nothing at all.
            BodyState.ABSENT_FINAL -> content?.trim()?.takeIf { it.isNotEmpty() } ?: title.trim()
            BodyState.PENDING -> null
        }
}
