package com.personalos.app.ui.news

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.personalos.app.core.tag.Tags
import com.personalos.app.core.tile.ActionKind
import com.personalos.app.core.tile.TileConfig
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.Event
import com.personalos.app.data.TaggedEvent
import com.personalos.app.ui.common.DAY_MS
import com.personalos.app.ui.common.DayMarker
import com.personalos.app.ui.common.DottedRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.TagLine
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.common.dayLabelRight
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import com.personalos.app.ui.tile.TileScaffold
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * News: everything editorial, ranked nearest-first.
 *
 * Reads the `news` **marker** tag through `item_tags_current`, so an item is
 * never lost to a tagger rollout and never double-counted across versions.
 *
 * Ranking goes through [com.personalos.app.core.place.PlaceRanker]. Today that is
 * `RecencyPlaceRanker`, which honestly reports a single tier because the
 * gazetteer, home places and radius do not exist yet (§9). When they land, the
 * tier headers below become `city -> state -> surrounding states -> country`
 * with national events pinned, and nothing in this file changes shape.
 */
private val NEWS_TILE =
    TileConfig(
        id = "news",
        title = "News",
        searchHint = "Search news",
        actions = listOf(ActionKind.SYNC, ActionKind.ADD_FEED, ActionKind.ADD_RULE, ActionKind.SETTINGS),
    )

private const val PAGE_SIZE = 40

/** How many articles one manual sync enriches; the scheduled job does more. */
private const val SYNC_ENRICH_BATCH = 10

/**
 * Titles are held to exactly two lines so every row is one height. Two is the
 * common case for a headline at this width; a third line is where a row starts
 * to balloon, so it is ellipsised instead.
 */
private const val TITLE_LINES = 2

private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun NewsScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Destination) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val database = remember { AppDatabase.getInstance(context) }
    val dao = database.eventDao()
    val scope = rememberCoroutineScope()

    // Pull feeds once on open; cached, so repeat opens are cheap.
    LaunchedEffect(Unit) { container.feeds.refresh() }

    val listState = rememberLazyListState()
    var items by remember { mutableStateOf<List<TaggedEvent>>(emptyList()) }
    var cursorTs by remember { mutableStateOf<Long?>(null) }
    var cursorId by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }

    // Bumped after enrichment so newly-filled summaries appear without waiting
    // for a new row: enrichment rewrites content, it does not change the max id.
    var reloadToken by remember { mutableStateOf(0) }

    val maxId by dao.observeMaxId().collectAsStateWithLifecycle(initialValue = null)
    val total by dao.observeCountByTag(Tags.NEWS).collectAsStateWithLifecycle(initialValue = 0)

    LaunchedEffect(maxId, reloadToken) {
        cursorTs = null
        cursorId = 0L
        endReached = false
        loading = true
        val first = dao.pageByTag(Tags.NEWS, null, 0L, PAGE_SIZE)
        items = first
        if (first.isNotEmpty()) {
            cursorTs = first.last().event.timestamp
            cursorId = first.last().event.id
        }
        if (first.size < PAGE_SIZE) endReached = true
        loading = false
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            if (!loading && !endReached && items.isNotEmpty() && lastVisible >= items.size - 5) {
                loading = true
                val next = dao.pageByTag(Tags.NEWS, cursorTs, cursorId, PAGE_SIZE)
                if (next.isEmpty()) {
                    endReached = true
                } else {
                    items = items + next
                    cursorTs = next.last().event.timestamp
                    cursorId = next.last().event.id
                    if (next.size < PAGE_SIZE) endReached = true
                }
                loading = false
            }
        }
    }

    TileScaffold(
        config = NEWS_TILE,
        modifier = modifier,
        accent = CategoryColors.Indigo,
        onBack = onBack,
        header = {
            if (total > 0) {
                Text(text = "$total ITEMS", style = RadarType.micro, color = RadarColors.paper4)
            }
        },
        onAction = { action ->
            when (action) {
                ActionKind.SYNC ->
                    scope.launch {
                        container.sync.run("news") {
                            container.sync.step("refreshing feeds")
                            val added = container.feeds.refresh()

                            // Enrichment fetches whole article pages, so a manual
                            // sync takes one bounded batch; repeated syncs keep
                            // filling in detail, which is the point of doing it
                            // progressively.
                            container.sync.step("enriching (new items: $added)")
                            val enriched = container.enricher.enrichBatch(limit = SYNC_ENRICH_BATCH)

                            reloadToken++
                            container.sync.step("done: +$added items, +$enriched summaries")
                        }
                    }

                else -> Unit
            }
        },
    ) { query ->
        val filtered =
            remember(items, query) {
                val q = query.trim()
                if (q.isEmpty()) {
                    items
                } else {
                    items.filter {
                        it.event.title.contains(q, ignoreCase = true) ||
                            it.event.content.contains(q, ignoreCase = true)
                    }
                }
            }

        // Tiers come from the ranker; tags are looked up by id afterwards, since
        // the ranker speaks in domain Events and does not need to know about tags.
        val tagsById =
            remember(filtered) {
                filtered.associate { it.event.id to it.tagList }
            }
        val tiers =
            remember(filtered, container.placeRanker) {
                container.placeRanker.tiers(filtered.map { it.event.toDomain() })
            }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            tiers.forEach { tier ->
                item(key = "tier:${tier.label}") {
                    WidgetHeader(title = tier.label, note = tier.events.size.toString())
                }
                // Day markers inside each tier, so the list reads as a timeline
                // the way Radar and Messages do. When real place tiers arrive
                // (§9) this nests unchanged: place groups containing days.
                val rows =
                    buildList {
                        var currentDay = Long.MIN_VALUE
                        tier.events.forEach { event ->
                            val day = event.timestamp / DAY_MS
                            if (day != currentDay) {
                                currentDay = day
                                add(NewsListRow.Day(day * DAY_MS))
                            }
                            add(NewsListRow.Item(event, tagsById[event.id].orEmpty()))
                        }
                    }

                items(
                    items = rows,
                    key = { row ->
                        when (row) {
                            is NewsListRow.Day -> "day:${row.dayStart}"
                            is NewsListRow.Item -> "event:${row.event.id}"
                        }
                    },
                ) { row ->
                    when (row) {
                        is NewsListRow.Day -> {
                            val label = remember(row.dayStart) { dayLabel(row.dayStart) }
                            DayMarker(label = label, right = dayLabelRight(label, row.dayStart))
                        }

                        is NewsListRow.Item ->
                            NewsRow(
                                event = row.event,
                                tags = row.tags,
                                onOpen = { onNavigate(Destination.Article(row.event.id)) },
                            )
                    }
                }
            }
        }
    }
}

/** A flattened timeline row: either a day divider or an article. */
private sealed interface NewsListRow {
    data class Day(
        val dayStart: Long,
    ) : NewsListRow

    data class Item(
        val event: Event,
        val tags: List<String>,
    ) : NewsListRow
}

@Composable
private fun NewsRow(
    event: Event,
    tags: List<String>,
    onOpen: () -> Unit,
) {
    // Source first, then the tags as chips - so a Mint story read through the
    // News tile still shows it is also `finance`. The `news` marker is implied by
    // the tile and suppressed; see TagLine. The label is the source's own name;
    // see sourceDisplayName.
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = TIME_FORMAT.format(Date(event.timestamp)),
                style = RadarType.monoSmall,
                color = RadarColors.ink3,
                modifier = Modifier.width(44.dp),
            )
            Column(Modifier.weight(1f)) {
                // A fixed two-line block with the headline centred inside it.
                // Clamping with minLines instead reserved the slack *below* the
                // text, so a one-line headline pushed its meta line down and the
                // row read as taller even when the total height matched. Centring
                // splits the slack and holds every meta line on the same baseline.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(titleBlockHeight())
                            .clickable { onOpen() },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = event.title,
                        style = RadarType.serifTitle,
                        color = RadarColors.ink,
                        maxLines = TITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Dotted rule + 4dp either side: the meta stops hugging the
                // headline and reads as secondary, without opening a gap.
                Spacer(Modifier.height(4.dp))
                DottedRule()
                Spacer(Modifier.height(4.dp))
                TagLine(tags = tags, source = event.source)
            }
        }
        SoftRule()
    }
}

/**
 * Height of the title block: [TITLE_LINES] lines of `serifTitle`.
 *
 * Derived from the type token's `lineHeight` rather than hardcoded in dp, so the
 * block still grows with the system font size instead of clipping the headline.
 */
@Composable
private fun titleBlockHeight(): Dp =
    with(LocalDensity.current) {
        (RadarType.serifTitle.lineHeight * TITLE_LINES).toDp()
    }
