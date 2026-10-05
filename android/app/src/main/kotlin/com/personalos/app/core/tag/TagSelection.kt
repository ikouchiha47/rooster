package com.personalos.app.core.tag

/** An item's chosen tags, the confidence of the choice, and whether it was a close call. */
data class TagSelection(
    val tags: Set<String>,
    val confidence: Float,
    /** Nothing cleared the bar, but a candidate was near it — a question for a later rung. */
    val unsure: Boolean = false,
)

/** Floors on the **centered** cosine (see [EmbeddingTagger]). */
const val SUBJECT_MIN = 0.55f
const val NATURE_MIN = 0.45f

/** Subjects are few (a thing is about one or two things); natures can stack a little. */
const val SUBJECT_CAP = 2
const val NATURE_CAP = 3

/**
 * How far below the group's leader a tag may still be kept. A tag close to the
 * leader rides along; a distant also-ran (a whole other subject) does not.
 */
const val MARGIN = 0.10f

/**
 * Turns per-tag similarity scores into a tag set, applying the taxonomy's rules —
 * the part that is *decisions*, not arithmetic, so it is pure and tested without a
 * model:
 *
 *  - **Markers are never inferred.** `news` is not in [TagGroups.SUBJECTS] or
 *    [TagGroups.NATURES], so a score for it is ignored; only [declaredTags] (the
 *    ingest path) can carry it.
 *  - **Subjects are gated on advertising.** Marketing copy is not evidence of
 *    subject matter ("Monsoon Sale" is not weather).
 *  - **Subjects are strong, natures are soft.** Different floors and caps.
 *  - **A distant also-ran is dropped.** Within a group only tags within [margin]
 *    of the leader are kept, so one clear winner does not drag a vague runner-up.
 *  - **Abstention is honest.** If nothing clears a floor but a candidate is within
 *    [margin] of it, the result is `unsure` and carries no tag, rather than a guess.
 *  - **Declared tags are kept.** A specialist source declares its subject; the
 *    model may add, never remove.
 */
fun selectTags(
    scores: Map<String, Float>,
    declaredTags: Set<String>,
    fromAdvert: Boolean,
    subjectMin: Float = SUBJECT_MIN,
    natureMin: Float = NATURE_MIN,
    subjectCap: Int = SUBJECT_CAP,
    natureCap: Int = NATURE_CAP,
    margin: Float = MARGIN,
): TagSelection {
    val subjects = scores.rankedIn(TagGroups.SUBJECTS)
    val natures = scores.rankedIn(TagGroups.NATURES)

    val keptSubjects = if (fromAdvert) emptyList() else subjects.leaders(subjectMin, margin, subjectCap)
    val keptNatures = natures.leaders(natureMin, margin, natureCap)
    val inferred = keptSubjects + keptNatures

    val tags =
        linkedSetOf<String>().apply {
            addAll(declaredTags)
            inferred.forEach { add(it.key) }
        }

    val confidence =
        when {
            inferred.isNotEmpty() -> inferred.maxOf { it.value }
            declaredTags.isNotEmpty() -> 1f
            else -> 0f
        }

    val unsure =
        inferred.isEmpty() &&
            (subjects.isBorderline(subjectMin, margin) || natures.isBorderline(natureMin, margin))

    return TagSelection(tags, confidence, unsure)
}

/** Tags in [group], best first (regardless of any floor). */
private fun Map<String, Float>.rankedIn(group: Set<String>): List<Map.Entry<String, Float>> = entries.filter { it.key in group }.sortedByDescending { it.value }

/** Those clearing [min], and within [margin] of the leader, capped at [cap]. */
private fun List<Map.Entry<String, Float>>.leaders(
    min: Float,
    margin: Float,
    cap: Int,
): List<Map.Entry<String, Float>> {
    val above = filter { it.value >= min }
    if (above.isEmpty()) return emptyList()
    val best = above.first().value
    return above.filter { it.value >= best - margin }.take(cap)
}

/** The best score is under the floor but within [margin] of it. */
private fun List<Map.Entry<String, Float>>.isBorderline(
    min: Float,
    margin: Float,
): Boolean {
    val best = firstOrNull()?.value ?: return false
    return best >= min - margin && best < min
}
