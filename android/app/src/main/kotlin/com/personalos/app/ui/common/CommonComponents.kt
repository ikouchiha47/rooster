package com.personalos.app.ui.common

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.personalos.app.core.feed.FeedCatalog
import com.personalos.app.core.sources.GnewsUrl
import com.personalos.app.core.sources.SourceKeys
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlin.math.pow

/**
 * Explicit design tokens from style.css. The theme already carries the same
 * palette, but the components need the exact paper/rule values so hairlines
 * and bands stay true to the mockup.
 */
object RadarColors {
    val ink = Color(0xFF171513)
    val ink2 = Color(0xFF4A443C)
    val ink3 = Color(0xFF837B70)
    val rule = Color(0xFF171513)
    val ruleSoft = Color(0xFFCFC6B3)
    val ruleDot = Color(0xFFB8AE99)
    val paper = Color(0xFFF7F4EC)
    val paper2 = Color(0xFFFAF8F2)
    val paper3 = Color(0xFFEFEADB)
    val paper4 = Color(0xFFE4DCC7)
    val fresh = Color(0xFFFFFCF3)
}

// ---------------------------------------------------------------- rules

/** 1dp hard ink rule. */
@Composable
fun HardRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(RadarColors.rule))
}

/** 1dp soft hairline. */
@Composable
fun SoftRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(RadarColors.ruleSoft))
}

/** Dotted metadata rule. */
@Composable
fun DottedRule(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        val y = size.height / 2f
        drawLine(
            color = RadarColors.ruleDot,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = size.height.coerceAtLeast(1f),
            pathEffect =
                PathEffect.dashPathEffect(
                    floatArrayOf(1.5.dp.toPx(), 3.dp.toPx()),
                    0f,
                ),
        )
    }
}

/** Vertical dotted rule used beside the time gutter. */
@Composable
fun DottedRuleVertical(modifier: Modifier = Modifier) {
    Canvas(modifier.width(1.dp).fillMaxHeight()) {
        val x = size.width / 2f
        drawLine(
            color = RadarColors.ruleDot,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = size.width.coerceAtLeast(1f),
            pathEffect =
                PathEffect.dashPathEffect(
                    floatArrayOf(1.5.dp.toPx(), 3.dp.toPx()),
                    0f,
                ),
        )
    }
}

/** 3dp category spine. */
@Composable
fun CategorySpine(
    color: Color = CategoryColors.Vermilion,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxHeight().width(3.dp).background(color))
}

// ---------------------------------------------------------------- labels

/**
 * Human name for an `events.source` value such as `rss:thehindu-top`.
 *
 * The feed catalog already carries a real `name` for every source. The tiles used
 * to ignore it and de-slug the id instead, which is why the News list read
 * "THEHINDU TOP" - a feed id leaking into the UI, and a different-looking label in
 * each of the three places that derived it. One name per source, derived once.
 *
 * Source rows (`gnews:<slug>`) resolve through [sourceNames] — source string to
 * the source's user-visible name — so an item shows its SOURCE name. Anything
 * neither the catalog nor the sources know - an SMS sender, a removed feed, an
 * unknown source - falls back to a de-slugged id, so a label is never blank.
 */
fun sourceDisplayName(
    source: String,
    sourceNames: Map<String, String> = emptyMap(),
): String {
    // A search-source row is named by its query slug, which reads as a place
    // ("KOLKATA") rather than a provenance. The outlet already rides in the
    // headline ("... - NDTV"), so the label says where the row came from.
    if (source.startsWith(GnewsUrl.SOURCE_PREFIX)) return sourceNames[source] ?: "Google News"
    if (source.startsWith(SourceKeys.USER_RSS_PREFIX)) return sourceNames[source] ?: "Feed"
    return sourceNames[source]
        ?: FeedCatalog.bySource(source)?.name
        ?: source
            .removePrefix(FeedCatalog.SOURCE_PREFIX)
            .removePrefix(GnewsUrl.SOURCE_PREFIX)
            .removePrefix(SourceKeys.USER_RSS_PREFIX)
            .replace('-', ' ')
}

@Composable
fun SourceLabel(
    source: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "— ${sourceDisplayName(source).uppercase()}",
        style = RadarType.micro,
        color = RadarColors.ink2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun Chip(
    text: String,
    color: Color = RadarColors.ink,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(color, RoundedCornerShape(2.dp)),
    ) {
        Text(
            text = text.uppercase(),
            style = RadarType.micro,
            color = if (computeLuminance(color) > 0.5f) RadarColors.ink else Color.White,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun EntityBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(RadarColors.paper, RoundedCornerShape(2.dp))
            .border(1.dp, RadarColors.ruleSoft, RoundedCornerShape(2.dp)),
    ) {
        Text(
            text = text,
            style = RadarType.microPlain,
            color = RadarColors.ink2,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** Vermilion square carrying the "PR" app mark. */
@Composable
fun AppMark(modifier: Modifier = Modifier.size(26.dp)) {
    Box(
        modifier.background(CategoryColors.Vermilion, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "PR",
            style = RadarType.labelMicro.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
            color = Color.White,
        )
    }
}

// ---------------------------------------------------------------- app bar

@Composable
fun GlyphActionButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(30.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, RadarColors.ink, 20.dp)
    }
}

/**
 * Top app bar: optional PR mark, serif title + letter-spaced caps sub,
 * right-hand glyph actions, closed by the heavy 3dp ink rule.
 *
 * The band owns the status-bar strip: its paper fill runs up under the cutout
 * while the title row below keeps the status-bar inset, so text never sits
 * under the camera. See [StatusBarIconsFor] for the icon side of the same rule.
 */
@Composable
fun RadarAppBar(
    title: String,
    sub: String? = null,
    mark: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    StatusBarIconsFor(RadarColors.paper2)
    Column(Modifier.fillMaxWidth().background(RadarColors.paper2)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mark) {
                AppMark()
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = RadarType.serifH3,
                    color = RadarColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sub != null) {
                    Text(
                        text = sub.uppercase(),
                        style = RadarType.micro,
                        color = RadarColors.ink3,
                        maxLines = 1,
                    )
                }
            }
            if (actions != null) {
                Spacer(Modifier.width(4.dp))
                actions()
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(RadarColors.ink))
    }
}

// ---------------------------------------------------------------- section header

@Composable
fun SectionHeader(
    title: String,
    accent: Color,
    note: String? = null,
    onInk: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bandColor = if (onInk) RadarColors.ink else RadarColors.paper3
    val titleColor = if (onInk) RadarColors.paper2 else RadarColors.ink
    val noteColor = if (onInk) RadarColors.paper4 else RadarColors.ink3

    Column(modifier.fillMaxWidth()) {
        HardRule()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(bandColor)
                    .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = RadarType.serifH4,
                color = titleColor,
                maxLines = 1,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (note != null) {
                Text(
                    text = note.uppercase(),
                    style = RadarType.micro,
                    color = noteColor,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        HardRule()
    }
}

// ---------------------------------------------------------------- search + quick actions

/**
 * The one search field in the app.
 *
 * Display-only when [query] is omitted (as on Home); pass a query and a change
 * handler to make it editable. Tiles do the latter so their search looks exactly
 * like Home's instead of forking the style.
 */
@Composable
fun SearchField(
    hint: String,
    modifier: Modifier = Modifier,
    query: String? = null,
    onQueryChange: ((String) -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(RadarColors.paper, RoundedCornerShape(2.dp))
            .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
            .padding(horizontal = 7.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Search, RadarColors.ink2, 15.dp)
        Spacer(Modifier.width(6.dp))
        if (query == null || onQueryChange == null) {
            Text(
                text = hint,
                style = RadarType.small,
                color = RadarColors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = hint,
                        style = RadarType.small,
                        color = RadarColors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = RadarType.small.copy(color = RadarColors.ink),
                    cursorBrush = SolidColor(RadarColors.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (onSubmit != null) {
                Spacer(Modifier.width(6.dp))
                // Search runs when this is pressed, not on every keystroke: the
                // query is only committed here. A drawn arrow in a bordered
                // square, not a text arrow: same ink and size as the leading
                // search icon, same paper fill and 1dp ink border as the bar.
                Box(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .background(RadarColors.paper, RoundedCornerShape(2.dp))
                            .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                            .clickable(onClickLabel = "Search", onClick = onSubmit),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.Forward, RadarColors.ink2, 15.dp)
                }
            }
        }
    }
}

/**
 * Floating "N NEW" badge for lists fed by Room flows.
 *
 * A sync slides fresh items in at the top without telling the reader; this
 * badge appears only while the list is scrolled down, counts what landed above
 * the reader's position, and tapping it scrolls to the new items and clears
 * itself. The count is UI-side (items above the last seen head); the store is
 * untouched.
 *
 * Deliberately a 2dp rectangle, not a stadium pill: the paper bans pills, and
 * the ink fill with a paper hairline keeps it legible over both paper rows
 * and the ink day headers it may overlap.
 */
@Composable
fun NewItemsPill(
    count: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(RadarColors.ink, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.paper2, RoundedCornerShape(2.dp))
                .clickable(onClickLabel = "Show new items", onClick = onTap)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$count NEW",
            style = RadarType.micro,
            color = RadarColors.paper2,
            maxLines = 1,
        )
    }
}

/**
 * Back affordance for screens that were **pushed** on top of another. Root tabs
 * do not get one - there is nowhere meaningful to go back to.
 *
 * Icon only, like every other navigation control in the app: the arrow is
 * unambiguous, and the word "back" next to it is noise.
 */
@Composable
fun BackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = RadarColors.ink,
) {
    Box(
        modifier =
            modifier
                .clickable { onBack() }
                .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        GlyphIcon(Glyph.Back, tint, 20.dp)
    }
}

/**
 * Status-bar icon contrast for the header background on screen.
 *
 * Every screen's header calls this with its own fill (accent band, paper2 bar,
 * paper row), so the icons flip per screen instead of once globally: dark
 * icons on light fills, light icons on dark ones. The threshold matches
 * [headerContentColor], so the icons always agree with the header's own title
 * treatment. MainActivity's launch setting stays only as the default before
 * the first header composes.
 */
@Composable
fun StatusBarIconsFor(background: Color) {
    val view = LocalView.current
    val darkIcons = computeLuminance(background) > 0.4f
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = darkIcons
    }
}

/**
 * The app's one screen header band.
 *
 * Used by a tile's root screen (through [com.personalos.app.ui.tile.TileScaffold])
 * and by the screens pushed on top of it (forecast, article), so drilling in
 * never changes the title's height or its tint. The title band is filled with the
 * service's own accent colour, which is what ties a detail screen back to the
 * tile it came from; text flips to ink on light accents so it stays legible.
 *
 * The band owns the status-bar strip: the accent fill runs up under the cutout
 * while the title row below keeps the status-bar inset, so the strip carries
 * this screen's own colour and text never sits under the camera.
 *
 * The title row is padded 12dp top and bottom. The title is serifH1 at 27sp on
 * a 29sp line - the largest type in the ramp - and 8dp made the band barely
 * taller than a 42dp service tile, a row rather than a masthead. 12dp gives a
 * band with presence while the horizontal 8dp gutter language stays dense; the
 * 30dp back button stays vertically centred, and everything below the hard
 * rule (subtitle, search, quick bar) is untouched.
 *
 * An optional [subtitle] (e.g. an article's tag line) renders *below* the band's
 * hard rule, back on paper - its colours are ink-based and would vanish on a dark
 * fill.
 */
@Composable
fun RadarHeader(
    title: String,
    accent: Color = RadarColors.ink,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val contentColor = headerContentColor(accent)
    StatusBarIconsFor(accent)
    Column(modifier.fillMaxWidth().background(accent)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                BackButton(onBack = onBack, tint = contentColor)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = RadarType.serifH1,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
        }
        HardRule()
        if (subtitle != null) {
            Box(Modifier.fillMaxWidth().background(RadarColors.paper2)) {
                subtitle()
            }
        }
    }
}

/** Readable ink for a filled [accent] band: dark ink on light accents, paper on dark. */
fun headerContentColor(accent: Color): Color = if (computeLuminance(accent) > 0.4f) RadarColors.ink else RadarColors.paper2

data class QuickItem(
    val label: String,
    val glyph: Glyph,
)

/**
 * Fixed height for the quick bar.
 *
 * Deliberately **not** `IntrinsicSize.Min` + `fillMaxHeight()`. With an
 * intrinsic row height the cells' own measured height can collapse, which gets
 * you a bar that draws perfectly and cannot be tapped - exactly the bug this
 * replaced. A concrete height keeps both the cells and the separators real.
 */
private val QuickBarHeight = 48.dp

@Composable
fun QuickBar(
    items: List<QuickItem>,
    onAction: (QuickItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(QuickBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onAction(item) }
                            .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    GlyphIcon(item.glyph, RadarColors.ink, 18.dp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.label.uppercase(),
                        style = RadarType.navLabel,
                        color = RadarColors.ink2,
                        maxLines = 1,
                    )
                }
                if (index != items.lastIndex) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
                }
            }
        }
        HardRule()
    }
}

// ---------------------------------------------------------------- widgets

@Composable
fun WidgetHeader(
    title: String,
    note: String? = null,
    glyph: Glyph? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RadarColors.paper3)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (glyph != null) {
                GlyphIcon(glyph, RadarColors.ink2, 15.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(text = title, style = RadarType.serifH4, color = RadarColors.ink, maxLines = 1)
            if (note != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = note.uppercase(),
                    style = RadarType.micro,
                    color = RadarColors.ink3,
                    maxLines = 1,
                )
            }
        }
        SoftRule()
    }
}

@Composable
fun StatCell(
    label: String,
    value: String,
    suffix: String? = null,
    valueColor: Color = RadarColors.ink,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(
            text = label.uppercase(),
            style = RadarType.micro,
            color = RadarColors.ink3,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        StatValue(value = value, suffix = suffix, valueColor = valueColor)
    }
}

@Composable
fun MiniCard(
    label: String,
    title: String,
    meta: String?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .background(RadarColors.paper2)
            .border(1.dp, RadarColors.ink),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
            Text(text = label.uppercase(), style = RadarType.micro, color = RadarColors.ink3, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(text = title, style = RadarType.serifSmall, color = RadarColors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta != null) {
                Spacer(Modifier.height(3.dp))
                Text(text = meta, style = RadarType.microPlain, color = RadarColors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- tabs

data class TabSpec(
    val label: String,
    val count: Int,
)

@Composable
fun TabStrip(
    items: List<TabSpec>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Keep the chosen tab visible: with more tabs than fit, selecting one from
    // an off-screen edge must not leave the strip showing the wrong end.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in items.indices) listState.animateScrollToItem(selectedIndex)
    }
    Column(modifier.fillMaxWidth().background(RadarColors.paper2)) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(items, key = { _, item -> item.label }) { index, item ->
                val selected = index == selectedIndex
                Row(
                    modifier =
                        Modifier
                            .clickable { onSelect(index) }
                            .background(if (selected) RadarColors.ink else Color.Transparent)
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        style = RadarType.label,
                        color = if (selected) RadarColors.paper2 else RadarColors.ink2,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = compactNumber(item.count),
                        style = RadarType.microPlain,
                        color = if (selected) RadarColors.paper4 else RadarColors.ink3,
                        maxLines = 1,
                    )
                }
                Box(Modifier.width(1.dp).height(26.dp).background(RadarColors.ruleSoft))
            }
        }
        HardRule()
    }
}

// ---------------------------------------------------------------- service tiles

data class TileSpec(
    val name: String,
    val color: Color,
    val glyph: Glyph,
    val outline: Boolean = false,
    val darkGlyph: Boolean = false,
)

/** Small 42dp flat square + 9px letter-spaced label, per the mockup. */
@Composable
fun ServiceTile(
    spec: TileSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val squareColor = if (spec.outline) RadarColors.paper else spec.color
    val glyphTint =
        when {
            spec.outline -> RadarColors.ink
            spec.darkGlyph -> RadarColors.ink
            else -> Color.White
        }

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(42.dp)
                    .background(squareColor, RoundedCornerShape(2.dp))
                    .then(
                        if (spec.outline) {
                            Modifier.border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(spec.glyph, glyphTint, 21.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = spec.name.uppercase(),
            style = RadarType.navLabel,
            color = RadarColors.ink2,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 4-column tile grid (rows of four, 4dp gutters, 10dp row gap). */
@Composable
fun TileGrid(
    items: List<TileSpec>,
    onTile: (TileSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        val rows = items.chunked(4)
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { spec ->
                    ServiceTile(
                        spec = spec,
                        onClick = { onTile(spec) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            if (rowIndex != rows.lastIndex) Spacer(Modifier.height(10.dp))
        }
    }
}

// ---------------------------------------------------------------- misc

@Composable
fun SyncLine(
    left: String,
    right: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RadarColors.paper3)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(CategoryColors.Teal))
                Spacer(Modifier.width(4.dp))
                Text(text = left.uppercase(), style = RadarType.micro, color = RadarColors.ink2, maxLines = 1)
            }
            Text(text = right.uppercase(), style = RadarType.micro, color = RadarColors.ink2, maxLines = 1)
        }
        HardRule()
    }
}

@Composable
fun DayMarker(
    label: String,
    right: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(RadarColors.ink)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label.uppercase(), style = RadarType.micro, color = RadarColors.paper2, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(RadarColors.ink3))
        Spacer(Modifier.width(8.dp))
        Text(text = right.uppercase(), style = RadarType.micro, color = RadarColors.paper2, maxLines = 1)
    }
}

/** Bottom-of-screen footer strip: hard rule + letter-spaced caps note. */
@Composable
fun FooterStrip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        HardRule()
        Text(
            text = text.uppercase(),
            style = RadarType.micro,
            color = RadarColors.ink3,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RadarColors.paper3)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun FlatButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    solid: Boolean = false,
    vermilion: Boolean = false,
) {
    val colors =
        when {
            vermilion ->
                ButtonDefaults.buttonColors(
                    containerColor = CategoryColors.Vermilion,
                    contentColor = Color.White,
                )
            solid ->
                ButtonDefaults.buttonColors(
                    containerColor = RadarColors.ink,
                    contentColor = RadarColors.paper2,
                )
            else ->
                ButtonDefaults.buttonColors(
                    containerColor = RadarColors.paper2,
                    contentColor = RadarColors.ink,
                )
        }

    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(2.dp),
        border = if (!solid && !vermilion) BorderStroke(1.dp, RadarColors.ink) else null,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = RadarType.labelMicro,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Relative luminance of an sRGB color (0..1). */
fun computeLuminance(color: Color): Float {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val r = channel(color.red)
    val g = channel(color.green)
    val b = channel(color.blue)
    return (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat()
}
