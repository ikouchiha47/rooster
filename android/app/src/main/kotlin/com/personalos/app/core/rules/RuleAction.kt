package com.personalos.app.core.rules

/**
 * A rule's action (ADR 0003 §9, plan T2.2): **delivery is the interruption**,
 * one target (`push` or nothing), while [position] is **emphasis** — it floats a
 * match above the stack rather than moving it. Delivery is never a location.
 *
 * Parsed from `rules.action_json` by [ActionJson].
 */
data class RuleAction(
    val delivery: Delivery,
    /** Surfacing emphasis; 0 means "no emphasis". */
    val position: Long,
)

/** The single delivery target a rule can have. */
enum class Delivery(
    val serialName: String,
) {
    PUSH("push"),
    NONE("none"),
    ;

    companion object {
        fun from(value: String): Delivery? = entries.firstOrNull { it.serialName == value }
    }
}
