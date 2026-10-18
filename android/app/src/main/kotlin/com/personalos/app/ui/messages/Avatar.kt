package com.personalos.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.computeLuminance
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/*
 * The conversation row's leading avatar. v1 is a **monogram only** — initials
 * over a colour derived from the sender. There are no contact photos and no
 * photo/contacts permission anywhere in this file; the label the row already
 * shows (a resolved contact name, a DLT header, or a bare number) is the only
 * input. Adding a photo later is a new source feeding the same tile, not a
 * branch in these pure functions.
 *
 * The two functions below are pure and JVM-testable: no Android, no Compose
 * runtime. The tile composable at the bottom is the only thing that reads the
 * theme.
 */

/** The avatar tile's square size. */
private val AvatarSize = 40.dp

/** Runs of whitespace, so an odd label cannot produce blank words. */
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * The stable colour set the avatar draws from. Deliberately the existing
 * [CategoryColors] palette — no new colours — ordered once so the index a
 * sender maps to never shifts.
 */
private val AvatarPalette: List<Color> =
    listOf(
        CategoryColors.Indigo,
        CategoryColors.Teal,
        CategoryColors.Plum,
        CategoryColors.Cyan,
        CategoryColors.Rust,
        CategoryColors.Vermilion,
        CategoryColors.Periwinkle,
        CategoryColors.Mustard,
        CategoryColors.Chartreuse,
    )

/**
 * One or two uppercase initials for a conversation's avatar, derived from the
 * label the row already displays.
 *
 * The label arrives in one of three shapes, and each gets the initials its
 * reader would pick:
 * - a contact name — the first letters of the first two words
 *   ("Ananya Rao" -> "AR");
 * - a DLT header — the brand token's first two letters, where the brand is the
 *   longest alphabetic token between the hyphens ("BV-BSNLWB-S" -> "BS",
 *   "AX-AMZNIN" -> "AM");
 * - a bare number — its last two digits ("918056428007" -> "07"), which is the
 *   end a reader recognises.
 *
 * A single-word name keeps its first two letters ("Ananya" -> "AN") so the tile
 * is not left with one lonely glyph. Anything with nothing to derive from —
 * blank, punctuation-only — gets "?", so a tile is never empty.
 */
fun avatarInitials(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return "?"

    // A bare number: no letters at all, but digits. The last two digits are the
    // part a person recognises on a phone number.
    if (trimmed.none { it.isLetter() }) {
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isNotEmpty()) return digits.takeLast(2).uppercase()
    }

    // A DLT header: hyphen-separated tokens, brand carried by the longest
    // alphabetic one.
    if (trimmed.contains('-')) {
        val brand =
            trimmed
                .split('-')
                .map { it.filter { c -> c.isLetterOrDigit() } }
                .filter { it.isNotEmpty() }
                .maxByOrNull { it.length }
        if (brand != null) return brand.take(2).uppercase()
    }

    // A name: initials of the first two words, or the first two letters when
    // there is only one word.
    val words = trimmed.split(WHITESPACE_RUN).filter { it.isNotBlank() }
    if (words.size >= 2) {
        val initials = listOfNotNull(initialOf(words[0]), initialOf(words[1])).joinToString("")
        if (initials.isNotEmpty()) return initials.uppercase()
    }
    val token = words.firstOrNull().orEmpty().filter { it.isLetterOrDigit() }
    return token.take(2).uppercase().ifEmpty { "?" }
}

/** The first letter or digit of [word], or null when it has neither. */
private fun initialOf(word: String): Char? = word.firstOrNull { it.isLetterOrDigit() }

/**
 * A deterministic palette index for a sender [key]. Same sender -> same colour,
 * on every device and across runs: a fixed FNV-1a hash over the string's UTF-16
 * code units, then modulo the palette size. Not `String.hashCode`, so the
 * mapping is this app's own and does not depend on the platform.
 *
 * A non-positive [paletteSize] has nothing to index, so it collapses to 0
 * rather than throwing.
 */
fun avatarColorIndex(
    key: String,
    paletteSize: Int,
): Int {
    if (paletteSize <= 0) return 0
    var hash = 0x811C9DC5.toInt()
    for (c in key) {
        hash = hash xor c.code
        hash *= 0x01000193
    }
    return (hash and 0x7FFFFFFF) % paletteSize
}

/**
 * The leading avatar tile: initials over a sender-stable colour, optionally
 * shown in the row. Contact photos are deferred — this draws a monogram and
 * nothing else, and reads no image or permission.
 */
@Composable
internal fun ConversationAvatar(
    label: String,
    modifier: Modifier = Modifier,
) {
    val initials = remember(label) { avatarInitials(label) }
    val color = remember(label) { AvatarPalette[avatarColorIndex(label, AvatarPalette.size)] }
    Box(
        modifier =
            modifier
                .size(AvatarSize)
                .background(color, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = RadarType.labelMicro.copy(fontSize = 12.sp, letterSpacing = 0.3.sp),
            color = if (computeLuminance(color) > 0.5f) RadarColors.ink else Color.White,
            maxLines = 1,
        )
    }
}
