package com.personalos.app.ui.watchers

import com.personalos.app.core.Chars
import com.personalos.app.data.RuleFire
import com.personalos.app.data.RuleWithFires
import com.personalos.app.ui.common.compactNumber

/**
 * Watchers' decisions, kept out of the composable so they can be tested without
 * rendering: what the header counts, what a rule's note says, which clauses are
 * shown as the reason, and the order rules are read in.
 *
 * Ordering is not cosmetic. A rule that fired an hour ago should be the first
 * thing read; a rule that has never fired is still worth listing, but at the
 * end — so activity is never buried under a long list of quiet rules.
 */
fun watchersOrder(groups: List<RuleWithFires>): List<RuleWithFires> =
    groups.sortedWith(
        compareBy<RuleWithFires> { it.fires.isEmpty() }
            .thenByDescending { it.fires.maxOfOrNull { fire -> fire.matchedAt } ?: Long.MIN_VALUE }
            .thenBy { it.rule.name.lowercase() },
    )

/** `3 RULES · 7 FIRES` — what the header band states. Counts every fire, not just the shown ones. */
fun watchersHeader(groups: List<RuleWithFires>): String {
    val fires = groups.sumOf { it.total }
    return "${compactNumber(groups.size)} RULES ${Chars.MIDDLE_DOT} ${compactNumber(fires)} FIRES"
}

/** A rule's fire count, or the honest "never fired" instead of a bare zero. */
fun ruleNote(group: RuleWithFires): String = if (group.total == 0) "never fired" else compactNumber(group.total)

/**
 * The lines that explain a fire: the clauses that held, each already carrying
 * the stored value that satisfied it. Capped, because past a few lines it stops
 * being a reason and starts being the whole condition.
 */
fun visibleClauses(fire: RuleFire): List<String> = fire.clauses.take(CLAUSES_SHOWN).map { it.summary }

/** Beyond this the rule's name carries the rest. */
private const val CLAUSES_SHOWN = 3
