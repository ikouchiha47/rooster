package com.personalos.app.data

/**
 * The one owner of what a rule colour may be.
 *
 * A rule's colour is its own, not its tag's: the rule's subject must never decide
 * how the row reads. Storing the exact string the UI renders keeps the stored
 * value and the drawn value the same fact.
 *
 * **Accepted form: `#RRGGBB`** — a `#` and exactly six hex digits, lower or
 * upper case. No alpha, no shorthand (`#RGB`), no named tokens. `#RRGGBB` is the
 * narrowest form that can still copy a value straight out of the theme palette,
 * and it round-trips through both a picker and a seed unchanged. A richer form
 * (alpha, tokens) would encode presentation decisions in the store before any
 * surface needs them; when one does, it can be added here, in one place.
 *
 * `null` is valid and means "no colour chosen" — it is a choice, not a malformed
 * value, and is never rejected. The UI decides how to render an absent colour.
 *
 * Validation lives here rather than in the DAO or a screen because create,
 * update and the seed reconcile all pass through it, so a value one path accepts
 * cannot be one another rejects (CODE-DESIGN-GUIDELINES.md §1).
 */
internal object RuleColor {
    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * Returns [color] unchanged when it is `null` or `#RRGGBB`.
     *
     * @throws IllegalArgumentException when [color] is neither `null` nor `#RRGGBB`.
     */
    fun requireValid(color: String?): String? {
        if (color == null) return null
        require(HEX.matches(color)) { "rule colour must be #RRGGBB, got '$color'" }
        return color
    }
}
