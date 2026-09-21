package com.personalos.app.ui.common

import kotlin.math.abs

/**
 * A count as the dense editorial pages read it: full digits below a thousand,
 * then `K` / `M` / `B` with two decimals. Pure, so every header and tab count
 * formats the same way and columns stay aligned under tabular figures.
 *
 * Example: `999` → `999`, `1500` → `1.50K`, `2_400_000` → `2.40M`,
 * `-1500` → `-1.50K`.
 */
fun compactNumber(value: Long): String {
    val magnitude = abs(value)
    val (unitValue, suffix) =
        when {
            magnitude < 1_000 -> return value.toString()
            magnitude < 1_000_000 -> value.toDouble() / 1_000 to "K"
            magnitude < 1_000_000_000 -> value.toDouble() / 1_000_000 to "M"
            else -> value.toDouble() / 1_000_000_000 to "B"
        }
    return "%.2f%s".format(unitValue, suffix)
}

/** [compactNumber] over an `Int` count, so callers never widen by hand. */
fun compactNumber(value: Int): String = compactNumber(value.toLong())
