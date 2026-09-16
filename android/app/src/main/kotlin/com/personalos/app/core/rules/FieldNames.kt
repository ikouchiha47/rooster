package com.personalos.app.core.rules

/**
 * The `item_fields` names a producer can supply today (ADR 0003 §3, §6, §13).
 *
 * A `field` predicate over a name no producer writes matches nothing, forever,
 * and reads as a broken engine rather than an absent fact — the defect ADR §13
 * was written to fix. So a shipped seed may only name a member of [SUPPLIED],
 * and [com.personalos.app.data.RuleSeeder]'s guard test enforces it.
 *
 * Producers reference the constants rather than spelling the strings, and this
 * set is the one declaration of what exists. Adding a field to a producer means
 * adding its name here — data, not a branch in the evaluator.
 */
object FieldNames {
    /** The sender address a message source already has (`SmsSource` today). */
    const val SENDER = "sender"

    /** A transaction amount parsed from a message body (`SmsSource` today). */
    const val AMOUNT = "amount"

    /** Every field name a producer can supply today. */
    val SUPPLIED: Set<String> = setOf(SENDER, AMOUNT)
}
