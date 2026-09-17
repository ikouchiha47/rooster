package com.personalos.app.data

import com.personalos.app.core.rules.Condition
import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.rules.RuleEvaluator
import com.personalos.app.core.rules.SeriesPredicateUnsupportedException
import com.personalos.app.core.rules.hasSeriesPredicate
import com.personalos.app.core.tag.TagGroups

/**
 * The stored facts a preview hit renders beyond its identity: the item's typed
 * extras ([fields]) and its place mention ([place]).
 *
 * [fields] is the store's own shape - `item_fields`' `FieldValue` map keyed by
 * name - so `FieldNames.AMOUNT` and `FieldNames.SENDER` arrive without a
 * parallel type, and a future producer field needs no change here. [place] is
 * the `MentionKind.PLACE` surface, the one mention the mockup's row shows.
 *
 * Both may be absent on an item that has neither, which is a valid hit: the row
 * simply has no extras to show (an empty map and a null place, never a crash or
 * a dropped row).
 */
data class RulePreviewHitFacts(
    val fields: Map<String, FieldValue> = emptyMap(),
    val place: String? = null,
)

/** One hit a preview can render: identity, source, title, when it landed, and its extras. */
data class RulePreviewHit(
    /** `events.ulid`. */
    val itemId: String,
    /** `events.source` - the source's identity, not its transport. */
    val sourceId: String,
    val title: String,
    /** `events.timestamp` (publish time), for the list's time column. */
    val timestamp: Long,
    /** Typed extras (`FieldNames.AMOUNT`, `FieldNames.SENDER`, ...) - the mockup's amount line. */
    val fields: Map<String, FieldValue> = emptyMap(),
    /** The place surface (`MentionKind.PLACE`) - the mockup's location. */
    val place: String? = null,
)

/**
 * What a preview found (ADR 0003 §12, requirement R4), shaped so the authoring
 * screen can render it without arithmetic:
 *
 *  - [Available.truncated] is the boolean the UI needs for "first N of more" -
 *    it never has to compare counts itself;
 *  - [Available.windowDays] is the literal the "last 7 days" label shows;
 *  - [Available.scannedCount] is how many candidates the evaluator saw, which is
 *    the N in that sentence;
 *  - [Available.sample] is already capped, ordered newest first.
 *
 * A rule the preview cannot evaluate is a first-class outcome, not an exception
 * or a false "0 matches" (CODE-DESIGN-GUIDELINES.md §3): [Unavailable] carries
 * the reason the UI states.
 */
sealed interface RulePreview {
    /** The condition was evaluated over stored history. Nothing was written. */
    data class Available(
        /** How many of the scanned candidates matched. */
        val matchedCount: Int,
        /** How many candidates the bounded loader handed to the evaluator. */
        val scannedCount: Int,
        /** True when the scan cap cut the candidate set short (more may exist). */
        val truncated: Boolean,
        /** The window the preview ran over, e.g. 7 for "last 7 days". */
        val windowDays: Int,
        /** The candidate cap that produced [scannedCount] and [truncated]. */
        val scanCap: Int,
        /** Up to the caller's sample limit, newest first. */
        val sample: List<RulePreviewHit>,
    ) : RulePreview

    /**
     * The rule cannot be previewed yet. [reason] is a **kind, not a sentence** — the UI
     * owns the wording, so a developer-facing explanation can never reach the screen.
     * [detail] carries the underlying cause for logs only, and is never shown.
     */
    data class Unavailable(
        val reason: UnavailableReason,
        val detail: String? = null,
    ) : RulePreview
}

/** Why a preview could not run. The UI supplies the words (CODE-DESIGN-GUIDELINES.md §3). */
enum class UnavailableReason {
    /** The condition is not valid JSON yet — a half-written draft. */
    INVALID_DRAFT,

    /** A series predicate, which the monitor engine evaluates instead (ADR 0003 §8). */
    SERIES_UNSUPPORTED,

    /** Anything unforeseen; [RulePreview.Unavailable.detail] carries the cause for logs. */
    UNKNOWN,
}

/** Candidate rows plus whether the scan cap cut them short. */
data class RulePreviewCandidates(
    val candidates: List<RulePreviewCandidate>,
    val truncated: Boolean,
)

/**
 * A condition a match must satisfy, extracted from a [Condition] so the preview
 * can narrow in SQL before the evaluator runs (ADR 0003 §12).
 *
 * These are **necessary, never sufficient**: every one is something the rule
 * requires, so applying them can only remove items the evaluator would reject
 * anyway. The evaluator remains the authority on a match.
 *
 * `text` (a regex cannot be indexed) and `field` are deliberately not modelled:
 * §12 puts them in the evaluator, and interpolating a user pattern into SQL is
 * worse than slow.
 */
internal sealed interface CoarseFilter {
    data class Source(
        val sourceId: String,
    ) : CoarseFilter

    data class Tag(
        val tag: String,
    ) : CoarseFilter

    data class Mention(
        val kind: String,
        val value: String,
    ) : CoarseFilter
}

/**
 * The filters a match to this condition must satisfy.
 *
 * `all` accumulates every child's filters (all of them are required); `any`
 * keeps only the filters common to every branch, because a match to an `any`
 * need hold for just one branch and narrowing on any single branch would drop
 * valid matches. A leaf's own predicate, or nothing when it is not indexable.
 */
internal fun Condition.coarseFilters(): Set<CoarseFilter> =
    when (this) {
        is Condition.All -> conditions.flatMapTo(linkedSetOf()) { it.coarseFilters() }
        is Condition.Any ->
            conditions
                .map { it.coarseFilters() }
                .reduceOrNull { common, next -> common intersect next }
                .orEmpty()
        is Condition.Subject -> setOf(CoarseFilter.Tag(tag))
        is Condition.Nature -> setOf(CoarseFilter.Tag(tag))
        is Condition.Marker -> TagGroups.MARKERS.mapTo(linkedSetOf()) { CoarseFilter.Tag(it) }
        is Condition.Mention -> setOf(CoarseFilter.Mention(kind, value))
        is Condition.Source -> setOf(CoarseFilter.Source(sourceId))
        // Not indexable, so left to the evaluator (ADR §12). Series predicates
        // are refused before the loader is ever asked for candidates.
        is Condition.Text,
        is Condition.Field,
        is Condition.Crossing,
        is Condition.Delta,
        is Condition.Min,
        is Condition.Max,
        -> emptySet()
    }

/**
 * Loads the bounded candidate set a preview evaluates.
 *
 * The date window and scan cap are always applied, so the query is predictable
 * and cannot walk the store. The condition's coarse filters further narrow it
 * **on indexed columns only**; whatever comes back is still only a candidate
 * set, and the evaluator decides the matches (ADR §12).
 */
class RulePreviewLoader(
    private val eventDao: EventDao,
) {
    suspend fun candidates(
        condition: Condition,
        since: Long,
        scanCap: Int,
    ): RulePreviewCandidates {
        require(scanCap > 0) { "scan cap must be positive, was $scanCap" }
        val filters = condition.coarseFilters()
        val source = filters.filterIsInstance<CoarseFilter.Source>().firstOrNull()?.sourceId
        val tag = filters.filterIsInstance<CoarseFilter.Tag>().firstOrNull()?.tag
        val mention = filters.filterIsInstance<CoarseFilter.Mention>().firstOrNull()
        // One more than the cap, so "was the cap hit?" is observable without a
        // second COUNT query. The extra row is dropped, never evaluated.
        val rows =
            eventDao.rulePreviewCandidates(
                since = since,
                sourceId = source,
                tag = tag,
                mentionKind = mention?.kind,
                mentionValue = mention?.value,
                limit = scanCap + 1,
            )
        return RulePreviewCandidates(
            candidates = rows.take(scanCap),
            truncated = rows.size > scanCap,
        )
    }
}

/**
 * The preview entry point the authoring screen calls (ADR 0003 §12, R4): the
 * dry run over stored history with a bounded, indexed SQL pre-filter, persisting
 * nothing.
 *
 * [RuleWriter] owns evaluation and the store reads for tags, mentions and
 * fields; this class owns only the SQL half - the bounded candidate load and the
 * coarse narrowing - so there is one evaluation path for a preview, ingest and a
 * rule edit alike.
 *
 * A condition that cannot be evaluated yet (today: a series predicate, whose
 * window arrives with monitors in slice 5) is caught at this boundary and
 * returned as [RulePreview.Unavailable]. The evaluator itself stays loud - this
 * is the preview refusing to put an exception in front of the UI, not the
 * evaluator going quiet.
 */
class RulePreviewer(
    private val loader: RulePreviewLoader,
    private val ruleWriter: RuleWriter,
    private val scanCap: Int = DEFAULT_SCAN_CAP,
    private val sampleLimit: Int = DEFAULT_SAMPLE_LIMIT,
) {
    /**
     * Evaluates [conditionJson] - a draft or a stored condition - over the last
     * [windowDays] of stored items and returns what would match.
     *
     * @param now injectable clock, so a window bound is testable.
     */
    suspend fun preview(
        conditionJson: String,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
        now: Long = System.currentTimeMillis(),
    ): RulePreview {
        val condition =
            runCatching { ConditionJson.parse(conditionJson) }
                .getOrElse { return RulePreview.Unavailable(UnavailableReason.INVALID_DRAFT, it.message) }
        try {
            RuleEvaluator.requireItemEvaluable(condition)
        } catch (e: SeriesPredicateUnsupportedException) {
            return RulePreview.Unavailable(UnavailableReason.SERIES_UNSUPPORTED, e.message ?: SERIES_UNAVAILABLE)
        }
        val since = now - windowDays.toLong() * DAY_MS
        val loaded = loader.candidates(condition, since, scanCap)
        val byId = loaded.candidates.associateBy { it.itemId }
        val matched = ruleWriter.dryRun(condition, loaded.candidates.map { it.toRuleSeed() })
        val sampleIds = matched.matchedItemIds.take(sampleLimit)
        // Only the sampled hits get their fields and place read. The evaluator
        // above saw every candidate - materialise is bounded by the scan cap -
        // but a hit row needs extras for a handful. Reading them for all 500
        // candidates would make the preview cost more than the ingest it
        // previews, so the read is bounded by sampleLimit, never by scanCap.
        val facts = ruleWriter.hitFacts(sampleIds)
        val sample = sampleIds.mapNotNull { id -> byId[id]?.toHit(facts[id]) }
        return RulePreview.Available(
            matchedCount = matched.count,
            scannedCount = loaded.candidates.size,
            truncated = loaded.truncated,
            windowDays = windowDays,
            scanCap = scanCap,
            sample = sample,
        )
    }

    /**
     * ADR 0005 T9: series preview over supplied windows — pure series leaves
     * only, persisting nothing. Item previews keep using [preview]; this is the
     * boundary where a series draft gets an honest verdict instead of an
     * exception in front of the UI.
     */
    fun previewSeries(
        conditionJson: String,
        windows: Map<String, List<Double>>,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): RulePreview {
        val condition =
            runCatching { ConditionJson.parse(conditionJson) }
                .getOrElse { return RulePreview.Unavailable(UnavailableReason.INVALID_DRAFT, it.message) }
        if (!condition.hasSeriesPredicate()) {
            return RulePreview.Unavailable(UnavailableReason.UNKNOWN, "not a series condition")
        }
        return try {
            val matched = ruleWriter.dryRunSeries(condition, windows)
            RulePreview.Available(
                matchedCount = matched.count,
                scannedCount = windows.size,
                truncated = false,
                windowDays = windowDays,
                scanCap = scanCap,
                sample = emptyList(),
            )
        } catch (e: Exception) {
            RulePreview.Unavailable(UnavailableReason.SERIES_UNSUPPORTED, e.message)
        }
    }

    companion object {
        /** The mockups' window: "last 7 days". */
        const val DEFAULT_WINDOW_DAYS = 7

        /** Bounds the load, so a preview is milliseconds rather than a full scan. */
        const val DEFAULT_SCAN_CAP = 500

        /** How many hits the preview hands back for the list; the UI may override. */
        const val DEFAULT_SAMPLE_LIMIT = 5

        private const val DAY_MS = 86_400_000L
        private const val SERIES_UNAVAILABLE =
            "series predicates cannot be previewed until the monitor engine lands (ADR 0003 §8)"
    }
}

private fun RulePreviewCandidate.toRuleSeed(): RuleItemSeed =
    RuleItemSeed(
        itemId = itemId,
        sourceId = sourceId,
        title = title,
        content = content,
    )

private fun RulePreviewCandidate.toHit(facts: RulePreviewHitFacts?): RulePreviewHit =
    RulePreviewHit(
        itemId = itemId,
        sourceId = sourceId,
        title = title,
        timestamp = timestamp,
        fields = facts?.fields.orEmpty(),
        place = facts?.place,
    )
