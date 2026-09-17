package com.personalos.app.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.model.FxRate
import com.personalos.app.core.tag.Tags
import com.personalos.app.core.tile.ActionKind
import com.personalos.app.core.tile.TileConfig
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.MentionEntity
import com.personalos.app.data.TaggedEvent
import com.personalos.app.ui.common.CategorySpine
import com.personalos.app.ui.common.DayMarker
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphIcon
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.NewItemsPill
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.TagLine
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.common.dayLabelRight
import com.personalos.app.ui.common.groupIntoDays
import com.personalos.app.ui.common.subjectSpineColor
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import com.personalos.app.ui.tile.TileScaffold
import kotlinx.coroutines.launch

/**
 * M&M (Money & Markets): the tracked FX pairs, and the finance *news* that
 * mentions them.
 *
 * Built on the Weather tile's shape, which is the point of the tile model:
 *  - rates come from [com.personalos.app.core.provider.FxProvider] - the same
 *    source Home's FX watch cell reads, so the two never drift;
 *  - the timeline below composes from the shared store through the `finance`
 *    tag, so a Mint markets story that arrived over RSS shows up here without
 *    M&M knowing anything about RSS.
 *
 * The lead pair is a slot of its own, like Weather's present location: when no
 * rates are available the lead card says so plainly rather than borrowing a
 * pair row, and the strip below never backfills it.
 *
 * Tapping a finance row opens the article, like News rows do.
 */
private val MONEY_TILE =
    TileConfig(
        id = "money",
        title = "Money and Market",
        searchHint = "Search markets",
        actions = listOf(ActionKind.SYNC, ActionKind.ADD_PAIR, ActionKind.ADD_RULE, ActionKind.SETTINGS),
    )

private const val FINANCE_NEWS_LIMIT = 25

/** One pair cell in the strip: fixed width and one shared row height. */
private val PAIR_CELL_WIDTH = 96.dp
private val PAIR_ROW_HEIGHT = 74.dp

@Composable
fun MoneyScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Destination) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val database = remember { AppDatabase.getInstance(context) }
    val dao = database.eventDao()
    val scope = rememberCoroutineScope()

    // Bumping this re-subscribes the provider's flow, which is what a manual sync
    // means for data that is already cached.
    var reload by remember { mutableStateOf(0) }

    val maxId by dao.observeMaxId().collectAsStateWithLifecycle(initialValue = null)

    // ADR 0005 T19: FX cells read the store. Pairs come from enabled fx
    // sources; numbers come from the latest observation per identity. Empty
    // store renders the existing honest empty, never a borrowed cache value.
    var rates by remember { mutableStateOf<List<FxRate>>(emptyList()) }
    LaunchedEffect(maxId, reload) {
        val fxSources =
            runCatching { database.sourceDao().enabled() }
                .getOrDefault(emptyList())
                .filter { it.kind == "fx" }
        val specs =
            fxSources.mapNotNull { row ->
                runCatching {
                    val spec =
                        com.personalos.app.core.sources.SourceSpecs
                            .parse(row.kind, row.specJson)
                            as com.personalos.app.core.sources.FxSpec
                    spec to
                        com.personalos.app.core.sources.SourceKeys
                            .sourceFor(row.id, spec)
                }.getOrNull()
            }
        rates =
            container.observationRepository.fxRates(
                specs.map { (spec, identity) -> spec.pair to identity },
            )
    }

    var news by remember { mutableStateOf<List<TaggedEvent>>(emptyList()) }
    var financeMentions by remember { mutableStateOf<Map<Long, List<MentionEntity>>>(emptyMap()) }

    LaunchedEffect(maxId, reload) {
        val page = dao.pageByTag(Tags.FINANCE, null, 0L, FINANCE_NEWS_LIMIT)
        news = page
        // Same one-batched-read-per-page shape as News: the timeline renders
        // first, mentions fill the meta lines without moving row heights.
        financeMentions =
            if (page.isEmpty()) {
                emptyMap()
            } else {
                val rows = database.mentionDao().forItems(page.map { it.event.ulid })
                val idByUlid = page.associate { it.event.ulid to it.event.id }
                rows.groupBy { idByUlid[it.itemId] ?: -1L }.filterKeys { it != -1L }
            }
    }

    val listState = rememberLazyListState()

    // Same new-items badge as News, for the finance timeline below: how many
    // rows landed above the reader while scrolled down. UI-side; the store
    // underneath is untouched.
    var pendingNews by remember { mutableStateOf(0) }
    var newsHeadSeenId by remember { mutableStateOf<Long?>(null) }
    var collapsedDays by remember { mutableStateOf(setOf<Long>()) }
    val awayFromTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    // A head change with the reader down means fresh finance items slid in
    // above: badge what sits above the last seen head. The reload path
    // re-reads the same page (same head), so it never trips the badge.
    LaunchedEffect(news) {
        val head = news.firstOrNull()?.event?.id
        val seen = newsHeadSeenId
        if (head == null) {
            newsHeadSeenId = null
            pendingNews = 0
        } else if (seen == null) {
            newsHeadSeenId = head
        } else if (head != seen) {
            val at = news.indexOfFirst { it.event.id == seen }
            val fresh = if (at < 0) news.size else at
            newsHeadSeenId = head
            pendingNews = if (awayFromTop && fresh > 0) pendingNews + fresh else 0
        }
    }

    // Reaching the top means the reader has seen everything: drop the badge.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> if (index == 0 && offset == 0) pendingNews = 0 }
    }

    TileScaffold(
        config = MONEY_TILE,
        modifier = modifier,
        accent = CategoryColors.Teal,
        onBack = onBack,
        header = {
            Text(
                text = "${rates.size} PAIRS",
                style = RadarType.micro,
                color = RadarColors.paper4,
            )
        },
        onAction = { action ->
            when (action) {
                ActionKind.SYNC ->
                    scope.launch {
                        container.sync.run("money") {
                            container.sync.step("refreshing rates")
                            runCatching { container.feeds.refresh(force = true) }
                            reload++
                            container.sync.step("done: ${rates.size} pairs")
                        }
                    }

                else -> Unit
            }
        },
    ) { query ->
        // Search filters both the strip and the timeline in memory, the way
        // Weather filters its places: display-only, the store is untouched.
        val pairs =
            remember(rates, query) {
                val q = query.trim()
                if (q.isEmpty()) {
                    rates
                } else {
                    rates.filter { it.pair.contains(q, ignoreCase = true) }
                }
            }
        val visible = remember(news, query) { filterFinanceNews(news, query) }

        // Day buckets for the finance timeline: display-only grouping over the
        // page, so the one batched mention read per page is untouched and row
        // keys stay `event:id`.
        val newsGroups = remember(visible) { groupIntoDays(visible) { it.event.timestamp } }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "fx") {
                    CardSection { FxLeadCard(rate = pairs.firstOrNull(), pairCount = rates.size) }
                }

                item(key = "pairs") {
                    CardSection {
                        PairsCard(
                            pairs = pairs,
                            emptyNote = if (rates.isEmpty()) "No pairs tracked." else "No pairs match.",
                        )
                    }
                }

                item(key = "news-header") {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        WidgetHeader(
                            title = "Markets & money",
                            note = visible.size.toString(),
                            glyph = Glyph.News,
                        )
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "news-empty") {
                        Text(
                            text =
                                if (query.trim().isEmpty()) {
                                    "Nothing tagged finance yet."
                                } else {
                                    "No markets stories match."
                                },
                            style = RadarType.body,
                            color = RadarColors.ink3,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
                newsGroups.forEach { group ->
                    stickyHeader(key = "mday:${group.dayStart}") {
                        val label = remember(group.dayStart) { dayLabel(group.dayStart) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClickLabel =
                                        if (group.dayStart in collapsedDays) "Expand day" else "Collapse day",
                                ) {
                                    collapsedDays =
                                        if (group.dayStart in collapsedDays) {
                                            collapsedDays - group.dayStart
                                        } else {
                                            collapsedDays + group.dayStart
                                        }
                                },
                        ) {
                            DayMarker(label = label, right = dayLabelRight(label, group.dayStart))
                        }
                    }
                    if (group.dayStart !in collapsedDays) {
                        items(group.items, key = { "event:${it.event.id}" }) { item ->
                            MoneyNewsLine(
                                item = item,
                                mentions = financeMentions[item.event.id].orEmpty(),
                                onOpen = { onNavigate(Destination.Article(item.event.id)) },
                            )
                        }
                    }
                }

                item(key = "footer") {
                    FooterStrip(
                        text =
                            "${rates.size} pairs ${Chars.MIDDLE_DOT} " +
                                "${visible.size} finance items ${Chars.MIDDLE_DOT} end of list",
                    )
                }
            }

            if (pendingNews > 0 && awayFromTop) {
                NewItemsPill(
                    count = pendingNews,
                    onTap = {
                        pendingNews = 0
                        scope.launch { listState.animateScrollToItem(2) }
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
            }
        }
    }
}

/** Inset that gives each bordered card its 8dp gutter without doubling it. */
@Composable
private fun CardSection(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp),
    ) { content() }
}

/**
 * The lead pair, as a wide confined card.
 *
 * When no rates are available it says so plainly - a dash, "not available",
 * and no invented number - the same honest-unavailable treatment Weather gives
 * its no-fix state.
 */
@Composable
private fun FxLeadCard(
    rate: FxRate?,
    pairCount: Int,
) {
    val available = rate != null
    Column(
        Modifier
            .fillMaxWidth()
            .background(RadarColors.paper2)
            .border(1.dp, RadarColors.ink),
    ) {
        WidgetHeader(
            title = "FX watch",
            note = rate?.note?.takeIf { it.isNotBlank() }?.uppercase() ?: "not available",
            glyph = Glyph.Banknote,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(
                glyph = Glyph.Banknote,
                tint = if (available) RadarColors.ink else RadarColors.ink3,
                size = 38.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = rate?.let(::formatFxRate) ?: Chars.EM_DASH,
                        style = RadarType.monoH2,
                        color = if (available) RadarColors.ink else RadarColors.ink3,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = rate?.pair?.uppercase() ?: "NOT AVAILABLE",
                        style = RadarType.labelMicro,
                        color = if (available) RadarColors.ink2 else RadarColors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text =
                        if (available) {
                            "$pairCount pairs tracked ${Chars.MIDDLE_DOT} ECB reference rates"
                        } else {
                            "rates unavailable"
                        },
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Every tracked pair, in one ruled card.
 *
 * A single horizontally-scrolling row of fixed-width cells - the same shape as
 * Weather's places strip - rather than an equal-width grid that wraps. Cells
 * keep one width and the row keeps one height whatever the count, so two pairs
 * and twelve pairs both read the same. Pairs have no drill-down screen, so
 * cells are static, unlike Weather's tappable places.
 */
@Composable
private fun PairsCard(
    pairs: List<FxRate>,
    emptyNote: String,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(RadarColors.paper2)
            .border(1.dp, RadarColors.ink),
    ) {
        WidgetHeader(
            title = "Pairs",
            note = pairs.size.takeIf { it > 0 }?.toString(),
            glyph = Glyph.Banknote,
        )
        if (pairs.isEmpty()) {
            Text(
                text = emptyNote,
                style = RadarType.small,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )
        } else {
            // Cells share the card width equally down to a 96dp floor, mirroring
            // Weather's places strip: the row fills edge to edge and only scrolls
            // on screens narrower than all minimums.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fits = maxWidth >= PAIR_CELL_WIDTH * pairs.size
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(PAIR_ROW_HEIGHT)
                            .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                ) {
                    pairs.forEachIndexed { index, rate ->
                        if (index > 0) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
                        }
                        PairCell(
                            rate = rate,
                            modifier =
                                if (fits) {
                                    Modifier.weight(1f).fillMaxHeight()
                                } else {
                                    Modifier.width(PAIR_CELL_WIDTH).fillMaxHeight()
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairCell(
    rate: FxRate,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            text = rate.pair,
            style = RadarType.micro,
            color = RadarColors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatFxRate(rate),
            style = RadarType.monoStat,
            color = RadarColors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = rate.note?.takeIf { it.isNotBlank() } ?: "tracked",
            style = RadarType.microPlain,
            color = RadarColors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One finance-tagged item, composed from the shared store rather than fetched. */
@Composable
private fun MoneyNewsLine(
    item: TaggedEvent,
    mentions: List<MentionEntity> = emptyList(),
    onOpen: () -> Unit = {},
) {
    // Same row language as News and Weather: 3dp subject spine at the edge,
    // fixed two-line headline block, one-line meta. The spine reads the FULL
    // tag list so the `finance` marker implied by this tile still colours the
    // edge Teal; the meta line gets the filtered list so it does not print
    // FINANCE forty times. Rows with no other subject than finance still scan
    // as finance.
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
        ) {
            CategorySpine(color = subjectSpineColor(item.tagList))
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                // Fixed two-line block, same shape as News and Weather:
                // fillMaxWidth wraps the paragraph first and only then
                // ellipsises; minLines pins the Text to the full block so every
                // row measures one height whatever the headline length.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(moneyTitleBlockHeight()),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = item.event.title,
                        style = RadarType.serifTitle,
                        color = RadarColors.ink,
                        minLines = MONEY_TITLE_LINES,
                        maxLines = MONEY_TITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(3.dp))
                // `finance` is implied by this tile, so it is dropped from the chips.
                TagLine(
                    tags = item.tagList.filter { it != Tags.FINANCE },
                    source = item.event.source,
                    mentions = mentions,
                )
            }
        }
        SoftRule()
    }
}

/**
 * Headlines are held to exactly two lines so every row is one height, the
 * same fixed block News and Weather use: a one-line headline centres inside it
 * instead of pulling its meta line up, so the list scans as a grid.
 */
private const val MONEY_TITLE_LINES = 2

@Composable
private fun moneyTitleBlockHeight(): Dp =
    with(LocalDensity.current) {
        (RadarType.serifTitle.lineHeight * MONEY_TITLE_LINES).toDp()
    }

/**
 * A rate as figures: two decimals, tabular mono downstream.
 *
 * UI-side formatting (docs/CODE-DESIGN-GUIDELINES.md §1): the provider owns the
 * number, this owns how it reads. Pure, so it is unit-tested without composing.
 */
internal fun formatFxRate(rate: FxRate): String = "%.2f".format(rate.rate)

/**
 * Search over the loaded finance page: blank matches everything, otherwise the
 * headline must contain the query.
 *
 * Display-only filtering in the UI, like Weather's places filter - the store
 * page underneath is untouched. Pure, so it is unit-tested without composing.
 */
internal fun filterFinanceNews(
    news: List<TaggedEvent>,
    query: String,
): List<TaggedEvent> {
    val q = query.trim()
    if (q.isEmpty()) return news
    return news.filter { it.event.title.contains(q, ignoreCase = true) }
}
