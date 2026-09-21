package com.personalos.app.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.tag.Tags
import com.personalos.app.core.tile.ActionKind
import com.personalos.app.core.tile.TileConfig
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.DayHeader
import com.personalos.app.data.Event
import com.personalos.app.data.MentionEntity
import com.personalos.app.data.TaggedEvent
import com.personalos.app.ui.common.CategorySpine
import com.personalos.app.ui.common.DAY_MS
import com.personalos.app.ui.common.DayMarker
import com.personalos.app.ui.common.DottedRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.NewItemsPill
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.TagLine
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.common.dayLabelRight
import com.personalos.app.ui.common.subjectSpineColor
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import com.personalos.app.ui.tile.TileScaffold
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * News: everything editorial, sectioned by day.
 *
 * Reads the `news` **marker** tag through `item_tags_current`, so an item is
 * never lost to a tagger rollout and never double-counted across versions.
 *
 * Headers first: one `GROUP BY day` query returns every day plus its count,
 * Today opens with the usual initial page, and any other day starts its own
 * keyset cursor when tapped open. A collapsed or never-opened day fetches zero
 * rows. Ordering stays publish-timestamp throughout; `ingested_at` only
 * decides which sync-bucket separator a Today row sits under.
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

private val SYNC_FMT = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

/** When the store was last filled: a date, or the honest absence of one. */
private fun syncLabel(lastSync: Long): String =
    if (lastSync <= 0L) {
        "NOT SYNCED YET"
    } else {
        "SYNCED ${SYNC_FMT.format(java.util.Date(lastSync)).uppercase()}"
    }

/** One day's keyset page: its loaded rows plus the cursor that pages it. */
private data class DayPage(
    val items: List<TaggedEvent> = emptyList(),
    val cursorTs: Long? = null,
    val cursorId: Long = 0L,
    val endReached: Boolean = false,
)

/** Last rendered index of a day plus whether it can still grow. */
private data class DayEnd(
    val lastIndex: Int,
    val endReached: Boolean,
)

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
    val mentionDao = database.mentionDao()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val listState = rememberLazyListState()
    var headers by remember { mutableStateOf<List<DayHeader>>(emptyList()) }
    var pages by remember { mutableStateOf<Map<Long, DayPage>>(emptyMap()) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var loadingDays by remember { mutableStateOf(setOf<Long>()) }
    var mentionsById by remember { mutableStateOf<Map<Long, List<MentionEntity>>>(emptyMap()) }

    // New-items pill: how many Today rows landed above the reader while scrolled
    // down, and the Today head id they were counted against. Both are UI-side;
    // the store underneath is untouched.
    var pendingNew by remember { mutableStateOf(0) }
    var todayHeadId by remember { mutableStateOf<Long?>(null) }
    var initialLoaded by remember { mutableStateOf(false) }

    val awayFromTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    // Bumped after enrichment so newly-filled summaries appear without waiting
    // for a new row: enrichment rewrites content, it does not change the max id.
    var reloadToken by remember { mutableStateOf(0) }

    val maxId by dao.observeMaxId().collectAsStateWithLifecycle(initialValue = null)
    val total by dao.observeCountByTag(Tags.NEWS).collectAsStateWithLifecycle(initialValue = 0)

    // One batched mention read per page, keyed back onto row ids. Sections
    // render from `pages` first; mentions fill the meta lines in without
    // changing any row's height (the TagLine clamp is one fixed line).
    suspend fun mentionsFor(page: List<TaggedEvent>): Map<Long, List<MentionEntity>> {
        if (page.isEmpty()) return emptyMap()
        val rows = mentionDao.forItems(page.map { it.event.ulid })
        if (rows.isEmpty()) return emptyMap()
        val idByUlid = page.associate { it.event.ulid to it.event.id }
        return rows.groupBy { idByUlid[it.itemId] ?: -1L }.filterKeys { it != -1L }
    }

    suspend fun refreshHeaders(): List<DayHeader> {
        val loaded = dao.dayHeadersByTag(Tags.NEWS)
        headers = loaded
        return loaded
    }

    // One keyset page inside a single day window. `reset` starts the day's
    // cursor over (open, pill tap, enrichment reload); otherwise the page
    // appends to the day's own cursor, so days paginate independently.
    suspend fun loadDay(
        dayStart: Long,
        reset: Boolean,
    ) {
        val prev = if (reset) DayPage() else pages[dayStart] ?: DayPage()
        if ((!reset && prev.endReached) || dayStart in loadingDays) return
        loadingDays = loadingDays + dayStart
        try {
            val rows =
                dao.pageByTagDay(
                    Tags.NEWS,
                    dayStart,
                    dayStart + DAY_MS,
                    if (reset) null else prev.cursorTs,
                    if (reset) 0L else prev.cursorId,
                    PAGE_SIZE,
                )
            val last = rows.lastOrNull()
            pages =
                pages +
                (
                    dayStart to
                        DayPage(
                            items = if (reset) rows else prev.items + rows,
                            cursorTs = last?.event?.timestamp ?: prev.cursorTs,
                            cursorId = last?.event?.id ?: prev.cursorId,
                            endReached = rows.size < PAGE_SIZE,
                        )
                )
            mentionsById = mentionsById + mentionsFor(rows)
        } finally {
            loadingDays = loadingDays - dayStart
        }
    }

    // Sync drift and midnight rollover: fresh headers, then the Today head
    // check. At the top the reader already sees the new rows, so Today
    // reloads in place; scrolled down, the gap badges as N NEW instead.
    suspend fun handleDrift() {
        val loaded = refreshHeaders()
        val today = todayStartMs()
        if (loaded.any { it.dayStart == today } && today !in expanded) {
            expanded = expanded + today
            loadDay(today, reset = true)
            todayHeadId =
                pages[today]
                    ?.items
                    ?.firstOrNull()
                    ?.event
                    ?.id
            return
        }
        if (today !in expanded) return
        val fresh = dao.pageByTagDay(Tags.NEWS, today, today + DAY_MS, null, 0L, PAGE_SIZE)
        val freshCount = freshCountAboveHead(fresh.map { it.event.id }, todayHeadId)
        if (freshCount <= 0) return
        if (awayFromTop) {
            pendingNew = pendingNew + freshCount
        } else {
            loadDay(today, reset = true)
            todayHeadId =
                pages[today]
                    ?.items
                    ?.firstOrNull()
                    ?.event
                    ?.id
        }
    }

    // Open from the store first, refresh behind it: a full poll of every feed
    // must never gate first paint. Fresh rows re-enter through `maxId`, which
    // re-checks headers and the Today head below.
    LaunchedEffect(Unit) {
        val loaded = refreshHeaders()
        val open = loaded.firstOrNull { it.dayStart == todayStartMs() } ?: loaded.firstOrNull()
        if (open != null) {
            expanded = expanded + open.dayStart
            loadDay(open.dayStart, reset = true)
            if (open.dayStart == todayStartMs()) {
                todayHeadId =
                    pages[open.dayStart]
                        ?.items
                        ?.firstOrNull()
                        ?.event
                        ?.id
            }
        }
        initialLoaded = true
        scope.launch {
            runCatching { container.feeds.refresh() }
        }
    }

    // A new max id means a sync slid rows in: re-check headers and the Today
    // head. Pagination only grows day tails and enrichment rewrites content,
    // so neither changes the heads this watches.
    LaunchedEffect(maxId) {
        if (!initialLoaded) return@LaunchedEffect
        handleDrift()
    }

    // Enrichment rewrites content in place: re-read the open days' heads so
    // newly-filled summaries appear.
    LaunchedEffect(reloadToken) {
        if (!initialLoaded) return@LaunchedEffect
        refreshHeaders()
        for (dayStart in expanded.toList()) loadDay(dayStart, reset = true)
        todayHeadId =
            pages[todayStartMs()]
                ?.items
                ?.firstOrNull()
                ?.event
                ?.id
    }

    // Re-query headers on resume: midnight moves Today, and a background sync
    // may have slid rows in while the tile was away.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && initialLoaded) {
                    scope.launch { handleDrift() }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Reaching the top means the reader has seen everything: drop the badge.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> if (index == 0 && offset == 0) pendingNew = 0 }
    }

    TileScaffold(
        config = NEWS_TILE,
        modifier = modifier,
        accent = CategoryColors.Indigo,
        onBack = onBack,
        header = {
            if (total > 0) {
                val lastSync by container.feeds.lastSyncAt.collectAsStateWithLifecycle(initialValue = 0L)
                Text(
                    text = "${compactNumber(total)} ITEMS ${Chars.MIDDLE_DOT} ${syncLabel(lastSync)}",
                    style = RadarType.micro,
                    color = RadarColors.paper4,
                )
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
        // Read fresh on every recomposition (headers reload on resume), so a
        // midnight rollover re-buckets Today without waiting for a relaunch.
        val today = todayStartMs()
        val itemsByDay = remember(pages) { pages.mapValues { it.value.items } }
        val sections = remember(headers, pages, expanded, query) { buildDaySections(headers, itemsByDay, today, query) }

        // Tags are looked up by id: the sections only shape loaded pages.
        val tagsById =
            remember(pages) {
                pages.values.flatMap { it.items }.associate { it.event.id to it.tagList }
            }

        // Rendered end index per day (headers plus Today bucket separators
        // counted), so scroll-near-end pages the day the reader is actually
        // leaving rather than always the list tail.
        val dayEnds =
            remember(sections, expanded, pages) {
                var pos = 0
                buildMap {
                    sections.forEach { section ->
                        pos += 1
                        if (section.dayStart in expanded) {
                            val rows = section.buckets?.sumOf { it.items.size + 1 } ?: section.rows.size
                            val end = pages[section.dayStart]?.endReached ?: true
                            put(section.dayStart, DayEnd(pos + rows - 1, end))
                            pos += rows
                        }
                    }
                }
            }

        LaunchedEffect(listState, dayEnds) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            }.collect { lastVisible ->
                dayEnds.forEach { (dayStart, end) ->
                    if (!end.endReached && dayStart !in loadingDays && lastVisible >= end.lastIndex - 5) {
                        scope.launch { loadDay(dayStart, reset = false) }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                sections.forEach { section ->
                    stickyHeader(key = "day:${section.dayStart}") {
                        val label = remember(section.dayStart) { dayLabel(section.dayStart) }
                        val date = remember(section.dayStart, label) { dayLabelRight(label, section.dayStart) }
                        val right = if (date.isEmpty()) "${section.total}" else "$date • ${section.total}"
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClickLabel =
                                        if (section.dayStart in expanded) "Collapse day" else "Expand day",
                                ) {
                                    if (section.dayStart in expanded) {
                                        expanded = expanded - section.dayStart
                                    } else {
                                        expanded = expanded + section.dayStart
                                        if (pages[section.dayStart] == null) {
                                            scope.launch { loadDay(section.dayStart, reset = true) }
                                        }
                                    }
                                },
                        ) {
                            DayMarker(label = label, right = right)
                        }
                    }
                    if (section.dayStart in expanded) {
                        if (section.buckets != null) {
                            section.buckets.forEach { bucket ->
                                item(key = "sync:${section.dayStart}:${bucket.bucketStart}") {
                                    SyncBucketSeparator(label = "Sync ${TIME_FORMAT.format(Date(bucket.bucketStart))}")
                                }
                                items(
                                    items = bucket.items,
                                    key = { "event:${it.event.id}" },
                                ) { row ->
                                    NewsRow(
                                        event = row.event.toDomain(),
                                        tags = tagsById[row.event.id].orEmpty(),
                                        mentions = mentionsById[row.event.id].orEmpty(),
                                        onOpen = { onNavigate(Destination.Article(row.event.id)) },
                                    )
                                }
                            }
                        } else {
                            items(
                                items = section.rows,
                                key = { "event:${it.event.id}" },
                            ) { row ->
                                NewsRow(
                                    event = row.event.toDomain(),
                                    tags = tagsById[row.event.id].orEmpty(),
                                    mentions = mentionsById[row.event.id].orEmpty(),
                                    onOpen = { onNavigate(Destination.Article(row.event.id)) },
                                )
                            }
                        }
                    }
                }
            }
            if (pendingNew > 0 && awayFromTop) {
                NewItemsPill(
                    count = pendingNew,
                    onTap = {
                        pendingNew = 0
                        scope.launch {
                            val open = todayStartMs()
                            if (open !in expanded) expanded = expanded + open
                            loadDay(open, reset = true)
                            todayHeadId =
                                pages[open]
                                    ?.items
                                    ?.firstOrNull()
                                    ?.event
                                    ?.id
                            listState.animateScrollToItem(0)
                        }
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Thin sync-bucket separator inside Today: micro label plus the dotted rule,
 * reusing the components and palette the rows already use. No new colours.
 */
@Composable
private fun SyncBucketSeparator(label: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RadarColors.paper3)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = RadarType.micro,
            color = RadarColors.ink3,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        DottedRule(Modifier.weight(1f))
    }
}

/** Shared with Travel's News tab, so both surfaces draw the same row. */
@Composable
internal fun NewsRow(
    event: Event,
    tags: List<String>,
    onOpen: () -> Unit,
    mentions: List<MentionEntity> = emptyList(),
) {
    // Source first, then the tags as chips - so a Mint story read through the
    // News tile still shows it is also `finance`. The `news` marker is implied by
    // the tile and suppressed; see TagLine. The label is the source's own name;
    // see sourceDisplayName.
    // The 3dp spine is the newspaper's scan layer, not decoration: it repeats
    // the leading subject chip's fill at the row edge (cream when there is no
    // subject), per the category spine in docs/DESIGN-GUIDELINES.md. Zebra
    // striping was rejected: it would fight the paper/fresh row tints and read
    // as a SaaS table, while the spine reuses a colour the row already earns.
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
        ) {
            CategorySpine(color = subjectSpineColor(tags))
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = TIME_FORMAT.format(Date(event.timestamp)),
                    style = RadarType.monoSmall,
                    color = RadarColors.ink3,
                    modifier = Modifier.width(44.dp),
                )
                Column(Modifier.weight(1f)) {
                    // The headline must WRAP to two lines, not just allow it. The
                    // old shape had maxLines=2 with no width modifier, and every
                    // screenshot showed a single ellipsised line: with no width
                    // modifier the Text measures wrap-content, so its paragraph
                    // takes its preferred single-line width and Ellipsis trims
                    // that one long line to the Box edge. maxLines never engages
                    // because the paragraph never wraps - nothing ever hands it
                    // the row's width as a wrap boundary. fillMaxWidth applies
                    // the Box width to the paragraph, so it wraps and only then
                    // ellipsises; minLines=2 pins the Text to the full block, so
                    // a one-line headline keeps its first baseline on the same
                    // grid as a two-line one instead of centring in the fixed
                    // Box. The fixed Box still fixes the outer height, so every
                    // row measures one height whatever the headline length.
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
                            minLines = TITLE_LINES,
                            maxLines = TITLE_LINES,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Dotted rule + 4dp either side: the meta stops hugging the
                    // headline and reads as secondary, without opening a gap.
                    Spacer(Modifier.height(4.dp))
                    DottedRule()
                    Spacer(Modifier.height(4.dp))
                    TagLine(tags = tags, source = event.source, mentions = mentions)
                }
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
