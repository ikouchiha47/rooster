package com.personalos.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.personalos.app.core.tag.Tags
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/**
 * Tag rendering for an event meta line, shared by News, Weather and Money.
 *
 * Tags are not one list (docs/ARCHITECTURE.md §10.5): a **subject** says what an
 * item is about, a **nature** says what kind of thing it is, and `news` is a
 * structural **marker**. They are rendered as three different things so the
 * distinction is legible without turning the line into a rainbow:
 *
 * - **Subject** - a filled accent chip. Colour is reserved for the one layer a
 *   reader scans by ("is this Money, Tech, Travel?"), matching the `.chip--money`
 *   / `.chip--tech` / ... classes in design/style.css.
 * - **Nature** - an outlined, uncoloured chip (`.chip--ghost`). The kind of thing
 *   is usually already obvious from the headline; it is a quiet second layer,
 *   told apart from subjects by form (outline vs. fill), not by more colour.
 * - **Marker** (`news`) - suppressed by default. In the News tile every single
 *   row carries it, so it adds the same word forty times and competes with the
 *   subjects; pass [showMarker] in a mixed feed (Home, Radar) where `news` still
 *   says something, and it renders as a muted chip so it never out-shouts a
 *   subject.
 *
 * Anything unmapped degrades to that same muted chip, so an unknown tag is
 * visible but never crashes and never claims a meaning it does not have.
 */
private val SUBJECT_COLORS: Map<String, Color> =
    mapOf(
        Tags.FINANCE to CategoryColors.Teal,
        Tags.TECH to CategoryColors.Plum,
        Tags.TRAVEL to CategoryColors.Mustard,
        Tags.WEATHER to CategoryColors.Chartreuse,
        // No documented accent fits scholarly output; Indigo is the editorial
        // ink and is only free because the `news` marker is muted, not filled.
        Tags.PAPER to CategoryColors.Indigo,
    )

/**
 * Natures (§10.5). `announcement` and `maintenance` are in the documented
 * vocabulary but have not been promoted to constants in [Tags] yet.
 */
private val NATURES: Set<String> =
    setOf(
        Tags.INCIDENT,
        Tags.PROMO,
        Tags.OFFICIAL,
        Tags.PERSONAL,
        Tags.EXPENSE,
        "announcement",
        "maintenance",
    )

private enum class TagStyle { SUBJECT, NATURE, MUTED }

/**
 * How many tags a one-line meta shows. At micro size a 360dp row fits the source
 * plus about this many chips; the rest fold into a `+N` rather than being dropped
 * without trace or allowed to wrap onto a second line.
 */
private const val MAX_VISIBLE_TAGS = 2

/**
 * A fixed one-line meta: optional [source] label, then [tags] as chips ordered
 * subjects -> natures -> the rest, so the coloured layer always leads.
 *
 * The line is deliberately clamped to a single chip row ([FlowRow] maxLines = 1)
 * so every event row is the same height no matter how many tags it carries -
 * a meta block that grew and shrank with its content is what stopped the News
 * list scanning as a grid. The source label is padded to a chip's height, so a
 * chip-less row measures exactly the same as one carrying chips. Tags past
 * [MAX_VISIBLE_TAGS] are summarised as a muted `+N`, never silently dropped.
 *
 * @param source raw `events.source`; resolved to a human name by sourceDisplayName
 *   and rendered as the leading label when not null.
 * @param showMarker whether to render the `news` marker as a muted chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagLine(
    tags: Collection<String>,
    modifier: Modifier = Modifier,
    source: String? = null,
    showMarker: Boolean = false,
) {
    val ordered = orderTags(tags, showMarker)
    val visible = ordered.take(MAX_VISIBLE_TAGS)
    val hidden = ordered.size - visible.size

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        maxLines = 1,
    ) {
        if (source != null) {
            SourceLabel(source = source, modifier = Modifier.padding(vertical = 1.dp))
        }
        visible.forEach { tag -> TagChip(tag = tag) }
        if (hidden > 0) {
            PlainTag(text = "+$hidden", color = RadarColors.ink3)
        }
    }
}

@Composable
private fun TagChip(tag: String) {
    val label = tag.uppercase()
    when (styleOf(tag)) {
        TagStyle.SUBJECT -> {
            val fill = SUBJECT_COLORS[tag.lowercase()] ?: RadarColors.ink2
            ChipBox(text = label, background = fill, content = onColor(fill))
        }
        // Natures and unknown tags are **plain text, never a box**. An outlined
        // or filled pill reads as a grey/white chip against the paper, which is
        // exactly what a tag must not look like. The layers still read apart by
        // form: a subject is the only thing that gets a fill.
        TagStyle.NATURE -> PlainTag(text = label, color = RadarColors.ink2)
        TagStyle.MUTED -> PlainTag(text = label, color = RadarColors.ink3)
    }
}

@Composable
private fun PlainTag(
    text: String,
    color: Color,
) {
    Text(text = text, style = RadarType.micro, color = color, maxLines = 1)
}

/** The one chip shape: a filled subject chip. Nothing else gets a box. */
@Composable
private fun ChipBox(
    text: String,
    background: Color,
    content: Color,
) {
    val shape = RoundedCornerShape(2.dp)
    Box(Modifier.background(background, shape)) {
        Text(
            text = text,
            style = RadarType.micro,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** Stable sort by group; equal ranks keep the input order. */
private fun orderTags(
    tags: Collection<String>,
    showMarker: Boolean,
): List<String> =
    tags
        .filter { showMarker || !it.equals(Tags.NEWS, ignoreCase = true) }
        .sortedBy { rankOf(it.lowercase()) }

private fun rankOf(tag: String): Int =
    when {
        tag in SUBJECT_COLORS -> 0
        tag in NATURES -> 1
        else -> 2
    }

private fun styleOf(tag: String): TagStyle {
    val key = tag.lowercase()
    return when {
        key in SUBJECT_COLORS -> TagStyle.SUBJECT
        key in NATURES -> TagStyle.NATURE
        else -> TagStyle.MUTED
    }
}

/** Ink or white for a filled chip, so Mustard/Chartreuse stay readable. */
private fun onColor(fill: Color): Color = if (computeLuminance(fill) > 0.5f) RadarColors.ink else Color.White
