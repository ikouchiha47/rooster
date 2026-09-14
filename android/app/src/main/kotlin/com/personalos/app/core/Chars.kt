package com.personalos.app.core

/**
 * Canonical punctuation and glyph characters.
 *
 * Design rule (docs/DESIGN-GUIDELINES.md, section 4): never hand-type stylised
 * punctuation - a literal em dash, arrow, smart quote or bullet is impossible to
 * review reliably and encodes inconsistently. Reference these constants instead.
 *
 * Use escapes (not literal characters) so the source file itself stays ASCII.
 */
object Chars {
    const val EM_DASH = "\u2014" // em dash
    const val EN_DASH = "\u2013" // en dash
    const val HYPHEN = "-"
    const val ARROW_RIGHT = "\u2192" // right arrow
    const val ARROW_LEFT = "\u2190" // left arrow
    const val MINUS = "\u2212" // minus sign (not a hyphen)
    const val MIDDLE_DOT = "\u00B7" // middle dot
    const val BULLET = "\u2022" // bullet
    const val ELLIPSIS = "\u2026" // ellipsis
    const val DEGREE = "\u00B0" // degree sign
    const val RUPEE = "\u20B9" // rupee sign
    const val TIMES = "\u00D7" // multiplication sign
    const val CHECK = "\u2713" // check mark
    const val WARNING = "\u26A0" // warning sign
    const val NBSP = "\u00A0" // non-breaking space
}
