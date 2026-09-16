package com.personalos.app.core.rules

/**
 * Parses `rules.action_json` into a [RuleAction] (ADR 0003 §9).
 *
 * ```json
 * {"delivery": "push", "position": 10}
 * ```
 *
 * `delivery` is required (`push` | `none`) and is the single interruption
 * target; `position` is optional emphasis and defaults to 0. Unknown keys are
 * rejected, so a misspelling fails loudly at write time (P7).
 *
 * @throws IllegalArgumentException on malformed JSON, an unknown key or value.
 */
object ActionJson {
    private val ALLOWED = setOf("delivery", "position")

    fun parse(json: String): RuleAction {
        val obj = parseObject(json, "action")
        obj.rejectUnknown(ALLOWED, "action")
        val deliveryName = obj.requireString("delivery", "action")
        val delivery =
            Delivery.from(deliveryName)
                ?: throw IllegalArgumentException(
                    "action: unknown delivery '$deliveryName'; expected one of ${Delivery.entries.map { it.serialName }.sorted()}",
                )
        val position = obj.longOr("position", 0L, "action")
        require(position >= 0) { "action: position must be >= 0, was $position" }
        return RuleAction(delivery = delivery, position = position)
    }
}
