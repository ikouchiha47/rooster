package com.personalos.app.ui.messages

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.SmsDetails
import com.personalos.app.core.category.CategoryTaxonomy
import com.personalos.app.core.time.UserZone
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.ConversationRepository
import com.personalos.app.data.EventDao
import com.personalos.app.data.EventEntity
import com.personalos.app.data.RuleFireRepository
import com.personalos.app.data.SmsSource
import com.personalos.app.ui.common.CategorySpine
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.DayMarker
import com.personalos.app.ui.common.DottedRule
import com.personalos.app.ui.common.DottedRuleVertical
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphActionButton
import com.personalos.app.ui.common.GlyphIcon
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarAppBar
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SearchField
import com.personalos.app.ui.common.SyncLine
import com.personalos.app.ui.common.TabSpec
import com.personalos.app.ui.common.TabStrip
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.common.dayLabelRight
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val PAGE_SIZE = 200
private const val DAY_MS = 86_400_000L

/**
 * Rows kept in memory. A merge drops the oldest beyond this, never the newest,
 * so a busy inbox cannot grow the loaded list without bound.
 */
private const val LOADED_CAP = 1_000

/**
 * The ordering the store uses (`ORDER BY timestamp DESC, id DESC`), so a merge
 * cannot disagree with the query about which row is "first". `id` is the
 * autoincrement row id, not the ULID — the same key the query orders by.
 */
private val newestMessageFirst: Comparator<EventEntity> =
    compareByDescending<EventEntity> { it.timestamp }.thenByDescending { it.id }

private sealed interface MsgRow {
    data class Header(
        val dayStart: Long,
    ) : MsgRow

    data class Item(
        val event: EventEntity,
    ) : MsgRow
}

@Composable
fun MessagesScreen(
    modifier: Modifier = Modifier,
    onOpenConversation: (String) -> Unit = {},
    onOpenContacts: () -> Unit = {},
    onNewMessage: () -> Unit = {},
    onOpenGroups: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val database = remember { AppDatabase.getInstance(context) }
    val dao = database.eventDao()

    // The taxonomy lives in the DB; load once so each row's chip reads its label
    // and colour from it rather than from a second classifier.
    val taxonomy by
        produceState<CategoryTaxonomy?>(initialValue = null, container) {
            value = runCatching { container.categoryStore.taxonomy() }.getOrNull()
        }

    // The strip is built from the taxonomy (plus Alerts), never an enum. Selection
    // is by id, so a category added as a DB row appears without touching this
    // screen. Before the taxonomy loads there is one tab (Alerts); once it lands
    // the default becomes the first category.
    val tabs = remember(taxonomy) { messageTabs(taxonomy) }
    var selectedTabId by remember { mutableStateOf<String?>(null) }
    val activeTabId =
        selectedTabId?.takeIf { id -> tabs.any { it.id == id } } ?: defaultMessageTabId(taxonomy)
    val selectedTabIndex = messageTabIndex(tabs, activeTabId)
    val activeCategoryId = tabs.firstOrNull { it.id == activeTabId }?.categoryId

    // Both conversation views read the same recency-ordered source; this toggle
    // only chooses whether it is grouped by day (Timeline, the default) or left
    // flat. Search stays flat on its own path: it is messages, not conversations.
    var conversationsView by remember { mutableStateOf(false) }

    // Saved is not a tab — it is a bookmark filter over messages. Kept as a
    // toggle so the swipe-to-save feature keeps a surface to read back from.
    var savedOnly by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    var items by remember { mutableStateOf<List<EventEntity>>(emptyList()) }
    var savedCursor by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var savedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    // Search state. `committed` is the query whose results are on screen, or
    // null in browse mode - kept separate from the field's `query` so typing
    // does not fight the store read. Search is scoped to Messages only; there is
    // no global search.
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var committed by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<EventEntity>>(emptyList()) }
    val searchListState = rememberLazyListState()

    // Two different triggers, deliberately kept apart:
    //  - the tab changed  → this is a different list, so resetting is correct;
    //  - a message landed → this is the *same* list plus rows; merging keeps
    //    every composed row alive instead of blanking and refilling.
    //
    // The signal is scoped to SMS. The global MAX(id) ticks for a weather
    // reading or an FX rate too, and waking this screen for those is what made
    // it flicker during a feed sync.
    val smsMaxId by database
        .messageSearchDao()
        .observeMaxIdForSource(SmsSource.SOURCE_ID)
        .collectAsStateWithLifecycle(initialValue = null)

    // The re-classify walk rewrites `events.category` on already-stored rows, which
    // the timeline (a suspend read) cannot see. It publishes its gate version, so
    // the list re-reads when a re-classify lands rather than staying stale until a
    // relaunch. New messages are the other trigger; the two are independent.
    val categoryVersion by container.categoryBackfill
        .observeVersion()
        .collectAsStateWithLifecycle(initialValue = null)
    var loadedOnce by remember { mutableStateOf(false) }

    // The saved list is the only message-level browse path left; the category
    // tabs list conversations. It reads the save stamp, not the publish time.
    LaunchedEffect(savedOnly) {
        if (!savedOnly) return@LaunchedEffect
        loadedOnce = false
        items = emptyList()
        savedCursor = null
        endReached = false
        loading = true
        val first = container.bookmarks.saved(source = SmsSource.SOURCE_ID, limit = PAGE_SIZE)
        items = first
        savedCursor = first.lastOrNull()?.bookmarkedAt
        if (first.size < PAGE_SIZE) endReached = true
        loading = false
        loadedOnce = true
    }

    // A message landed. Merge the newest page into the list that is already on
    // screen: no blanking, no `loading` flip, and the reader's rows are never
    // torn down. Skipped while search results are showing, because those are a
    // different list with a different order.
    LaunchedEffect(smsMaxId) {
        if (!savedOnly || !loadedOnce || committed != null) return@LaunchedEffect
        val fresh = container.bookmarks.saved(source = SmsSource.SOURCE_ID, limit = PAGE_SIZE)
        items =
            mergeArrivals(
                current = items,
                arrivals = fresh,
                key = { it.ulid },
                newestFirst = newestMessageFirst,
                cap = LOADED_CAP,
            )
    }

    // Re-read the saved flags for the loaded page, so the glyph and the swipe
    // reflect the store rather than a guess made at render time. Search results
    // are included: the same row rendering shows the same bookmark glyph.
    LaunchedEffect(items, results) {
        savedIds =
            (items + results)
                .filter { it.bookmarkedAt != null }
                .map { it.ulid }
                .toSet()
    }

    // The two row actions, in one place, because they must treat the browse
    // stream and a search result identically — an action that worked in one list
    // and silently missed the other is exactly how a row goes stale on screen.
    suspend fun toggleSave(ulid: String) {
        val nowSaved = container.bookmarks.toggle(ulid)
        savedIds = if (nowSaved) savedIds + ulid else savedIds - ulid
        // Only the Saved view has to drop the row: elsewhere the message stays,
        // it just stops being marked.
        if (!nowSaved && savedOnly) {
            items = items.filterNot { it.ulid == ulid }
            results = results.filterNot { it.ulid == ulid }
        }
    }

    suspend fun deleteMessage(ulid: String) {
        // App copy only; the message on the device stays.
        container.messageDeletes.remove(ulid)
        items = items.filterNot { it.ulid == ulid }
        results = results.filterNot { it.ulid == ulid }
        savedIds = savedIds - ulid
    }

    // ...and the next page when the saved list nears its end.
    LaunchedEffect(listState, savedOnly) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            // Never page the browse stream while search results are on screen.
            if (savedOnly &&
                committed == null &&
                !loading &&
                !endReached &&
                items.isNotEmpty() &&
                lastVisible >= items.size - 5
            ) {
                loading = true
                val next = container.bookmarks.saved(SmsSource.SOURCE_ID, savedCursor, PAGE_SIZE)
                if (next.isEmpty()) {
                    endReached = true
                } else {
                    items = items + next
                    savedCursor = next.lastOrNull()?.bookmarkedAt
                    if (next.size < PAGE_SIZE) endReached = true
                }
                loading = false
            }
        }
    }

    val rows =
        remember(items) {
            val out = ArrayList<MsgRow>(items.size + 8)
            var currentBucket = Long.MIN_VALUE
            for (e in items) {
                val bucket = UserZone.dayStart(e.timestamp)
                if (bucket != currentBucket) {
                    currentBucket = bucket
                    out.add(MsgRow.Header(e.timestamp))
                }
                out.add(MsgRow.Item(e))
            }
            out
        }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = RadarColors.paper2,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(RadarColors.paper2),
        ) {
            MessagesAppBar(
                dao = dao,
                conversationsView = conversationsView,
                savedOnly = savedOnly,
                onSearch = {
                    searchOpen = !searchOpen
                    if (!searchOpen) {
                        // Closing the field returns to the stream, with nothing stale.
                        query = ""
                        committed = null
                        results = emptyList()
                    }
                },
                onToggleView = { conversationsView = !conversationsView },
                onToggleSaved = { savedOnly = !savedOnly },
                onOpenContacts = onOpenContacts,
                onNewMessage = onNewMessage,
                onOpenGroups = onOpenGroups,
            )
            if (searchOpen) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(RadarColors.paper2)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    SearchField(
                        hint = "Search messages",
                        query = query,
                        onQueryChange = { text ->
                            query = text
                            if (text.isBlank()) {
                                // Clearing the field returns to browse; an empty
                                // result set is never left on screen.
                                committed = null
                                results = emptyList()
                            }
                        },
                        onSubmit = {
                            val submitted = query.trim()
                            if (submitted.isNotEmpty()) {
                                scope.launch {
                                    // The store read is scoped to SMS by SQL; this
                                    // is Messages search, not a global search.
                                    results = container.messageSearch.search(submitted, PAGE_SIZE)
                                    committed = submitted
                                }
                            }
                        },
                        trailing = {
                            // Cancel: drops the query, the results and the field in
                            // one tap, so leaving search never needs two.
                            Box(
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clickable(onClickLabel = "Cancel search") {
                                            searchOpen = false
                                            query = ""
                                            committed = null
                                            results = emptyList()
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                GlyphIcon(Glyph.Close, RadarColors.ink2, 15.dp)
                            }
                        },
                    )
                }
            }
            if (committed == null) {
                if (!savedOnly) {
                    MessagesTabs(
                        conversations = container.conversations,
                        ruleFires = container.ruleFires,
                        tabs = tabs,
                        selectedIndex = selectedTabIndex,
                        onSelect = { index ->
                            selectedTabId = tabs.getOrNull(index)?.id
                            // Choosing a category leaves the saved filter.
                            savedOnly = false
                        },
                    )
                }
                SyncLine(
                    left = if (savedOnly) "Saved ${Chars.MIDDLE_DOT} bookmarked messages" else "Push + in-app inbox",
                    right = if (savedOnly) "Newest save first" else "Quiet hours 23:00${Chars.EN_DASH}07:00",
                )
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                val activeQuery = committed
                when {
                    activeQuery != null ->
                        SearchResults(
                            query = activeQuery,
                            results = results,
                            savedIds = savedIds,
                            listState = searchListState,
                            taxonomy = taxonomy,
                            // A result is a message: the same save and delete
                            // actions apply, so it renders the same row.
                            onOpenConversation = onOpenConversation,
                            onToggleSave = { ulid -> scope.launch { toggleSave(ulid) } },
                            onDelete = { ulid -> scope.launch { deleteMessage(ulid) } },
                        )

                    // Saved is a message filter, not a category: one flat list,
                    // ordered by when it was saved.
                    savedOnly ->
                        if (rows.isEmpty()) {
                            EmptySaved(Modifier.fillMaxSize())
                        } else {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(
                                    items = rows,
                                    key = { row ->
                                        when (row) {
                                            is MsgRow.Header -> "d-${row.dayStart}"
                                            is MsgRow.Item -> "e-${row.event.id}"
                                        }
                                    },
                                ) { row ->
                                    when (row) {
                                        is MsgRow.Header -> {
                                            val (label, right) =
                                                remember(row.dayStart) {
                                                    val l = dayLabel(row.dayStart)
                                                    l to dayLabelRight(l, row.dayStart)
                                                }
                                            DayMarker(label = label, right = right)
                                        }

                                        is MsgRow.Item ->
                                            SwipeableMessageRow(
                                                entity = row.event,
                                                saved = row.event.ulid in savedIds,
                                                taxonomy = taxonomy,
                                                onOpen = { onOpenConversation(row.event.title) },
                                                onToggleSave = { scope.launch { toggleSave(row.event.ulid) } },
                                                onDelete = { scope.launch { deleteMessage(row.event.ulid) } },
                                            )
                                    }
                                }
                                item { FooterStrip("Last refresh 09:44 ${Chars.MIDDLE_DOT} sample data") }
                            }
                        }

                    // A category tab lists conversations whose latest message
                    // carries that category. One body, one ordering (recency via
                    // `timeline`); the toggle only chooses grouping: the Timeline
                    // buckets by day, the flat view does not.
                    activeCategoryId != null ->
                        ConversationsListBody(
                            repository = container.conversations,
                            categoryId = activeCategoryId,
                            smsMaxId = smsMaxId,
                            categoryVersion = categoryVersion,
                            groupByDay = !conversationsView,
                            onOpenConversation = onOpenConversation,
                        )

                    // Alerts: a cross-source view over stored rule matches — the
                    // same matches Watchers shows, flattened across every rule.
                    // Distinct from the SMS-scoped category tabs by design.
                    else -> AlertsListBody(repository = container.ruleFires)
                }
            }
        }
    }
}

// Counts are collected inside their own composable, so a count change
// recomposes only the app bar / tab strip, not the list.
@Composable
private fun MessagesAppBar(
    dao: EventDao,
    conversationsView: Boolean,
    savedOnly: Boolean,
    onSearch: () -> Unit,
    onToggleView: () -> Unit,
    onToggleSaved: () -> Unit,
    onOpenContacts: () -> Unit,
    onNewMessage: () -> Unit,
    onOpenGroups: () -> Unit,
) {
    val unread by dao.observeCountByMode("inbox").collectAsStateWithLifecycle(initialValue = 0)
    RadarAppBar(
        title = "Messages",
        sub = "Notifications ${Chars.MIDDLE_DOT} $unread unread",
        // Contacts sits before the title: the address book is the entry point
        // into a new message, so it leads rather than competes with the actions.
        leading = { GlyphActionButton(glyph = Glyph.Person, onClick = onOpenContacts) },
        actions = {
            Text(
                text = "+",
                style = RadarType.serifH3,
                color = RadarColors.ink,
                modifier = Modifier.clickable { onNewMessage() }.padding(horizontal = 10.dp),
            )
            // Flat conversations <-> day-bucketed timeline. Both list the same
            // recency-ordered source; day-bucketing is the only difference.
            GlyphActionButton(
                glyph = if (conversationsView) Glyph.Stack else Glyph.Chat,
                onClick = onToggleView,
            )
            // Saved is a filter over messages, not a tab. The filled mark shows
            // when it is on, so the surface is never silently somewhere else.
            GlyphActionButton(
                glyph = if (savedOnly) Glyph.BookmarkFilled else Glyph.Bookmark,
                onClick = onToggleSaved,
            )
            GlyphActionButton(Glyph.Search, onClick = onSearch)
            GlyphActionButton(Glyph.Dots, onClick = onOpenGroups)
        },
    )
}

/**
 * The strip is built from [tabs]: each category badge from the conversations
 * whose latest message carries that category — the same join and rule the list
 * uses — and the Alerts badge from the count of matched events, the same grain
 * the Alerts list shows. Both counts are read from the store, so a badge and its
 * list cannot disagree.
 */
@Composable
private fun MessagesTabs(
    conversations: ConversationRepository,
    ruleFires: RuleFireRepository,
    tabs: List<MessageTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val counts by
        conversations
            .observeCountsByCategory()
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val alerts by ruleFires.observeAlertCount().collectAsStateWithLifecycle(initialValue = 0)
    // Rebuild only when a count changes, not on every recomposition.
    val byCategory = remember(counts) { counts.associate { it.category to it.count } }

    TabStrip(
        items = tabs.map { TabSpec(it.label, messageTabCount(it, byCategory, alerts)) },
        selectedIndex = selectedIndex,
        onSelect = onSelect,
    )
}

/** How far the row slides to reveal its actions — one column each. */
private val REVEAL_WIDTH = 168.dp

private val ACTION_WIDTH = 84.dp

/**
 * A message row that slides left to **reveal** its action, stays open, and
 * closes once you tap it.
 *
 * Deliberately not a swipe-to-act gesture: sliding something and having it
 * spring back tells you nothing about whether it worked, and an action you can
 * see before you press it is the whole point. The row comes back only after the
 * action has run, so the movement is the receipt.
 *
 * A tap opens the sender's thread. Tap and swipe share one [pointerInput] as two
 * concurrent detectors rather than a `clickable` over the drag: a horizontal
 * drag past touch slop consumes its moves, which cancels the tap, so the two
 * cannot both fire. A tap with the actions already revealed just closes the row
 * — the same gesture, so navigation is never a surprise.
 */
@Composable
private fun SwipeableMessageRow(
    entity: EventEntity,
    saved: Boolean,
    taxonomy: CategoryTaxonomy?,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val revealPx = with(LocalDensity.current) { REVEAL_WIDTH.toPx() }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnOpen by rememberUpdatedState(onOpen)

    Box(Modifier.fillMaxWidth()) {
        // Behind the row: revealed as it slides, tap-only.
        Row(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(RadarColors.paper3),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowAction(
                glyph = if (saved) Glyph.BookmarkFilled else Glyph.Bookmark,
                label = if (saved) "UNSAVE" else "SAVE",
                tint = if (saved) CategoryColors.Teal else RadarColors.ink,
                onClick = {
                    scope.launch {
                        onToggleSave()
                        offsetX.animateTo(0f)
                    }
                },
            )
            RowAction(
                glyph = Glyph.Trash,
                label = "DELETE",
                tint = CategoryColors.Vermilion,
                onClick = onDelete,
            )
        }

        Row(
            modifier =
                Modifier
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .pointerInput(revealPx) {
                        // Both detectors run on the same pointer stream. The drag
                        // detector consumes only once touch slop is crossed, which
                        // cancels the tap; below slop the up is unconsumed and the
                        // tap wins. The row being open is the other guard: it
                        // closes rather than navigates.
                        coroutineScope {
                            launch {
                                detectTapGestures(
                                    onTap = {
                                        if (offsetX.value < 0f) {
                                            scope.launch { offsetX.animateTo(0f) }
                                        } else {
                                            currentOnOpen()
                                        }
                                    },
                                )
                            }
                            launch {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { _, dragAmount ->
                                        scope.launch {
                                            offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-revealPx, 0f))
                                        }
                                    },
                                    onDragEnd = {
                                        scope.launch {
                                            val open = offsetX.value < -revealPx / 2f
                                            offsetX.animateTo(if (open) -revealPx else 0f)
                                        }
                                    },
                                )
                            }
                        }
                    },
        ) {
            MessageRow(entity, saved = saved, taxonomy = taxonomy)
        }
    }
}

/** One revealed action: a glyph over its word, whole column tappable. */
@Composable
private fun RowAction(
    glyph: Glyph,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(ACTION_WIDTH)
                .fillMaxHeight()
                .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(glyph = glyph, tint = tint, size = 18.dp)
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = RadarType.micro, color = RadarColors.ink2)
    }
}

@Composable
private fun MessageRow(
    entity: EventEntity,
    taxonomy: CategoryTaxonomy?,
    saved: Boolean = false,
) {
    val unread = entity.type == "inbox"
    val time =
        remember(entity.timestamp) {
            formatMessageTime(System.currentTimeMillis(), entity.timestamp, UserZone.current)
        }
    // The stored sender is the fallback; a known contact name overlays it. Not
    // remembered: the resolver is empty at first composition and fills in later.
    val title = LocalAppContainer.current.contactNames.nameFor(entity.title) ?: entity.title
    // The chip reads the message's stored category id and the taxonomy's label
    // and colour. No classification happens here: ingest is the one owner.
    val category = remember(entity.category, taxonomy) { taxonomy?.category(entity.category) }
    // Display-only extras, never the category.
    val amount = remember(entity.content) { SmsDetails.amount(entity.content) }
    val code = remember(entity.content) { SmsDetails.code(entity.content) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (unread) RadarColors.fresh else RadarColors.paper2)
                .height(IntrinsicSize.Min),
    ) {
        CategorySpine(if (unread) CategoryColors.Vermilion else Color.Transparent)

        Column(
            modifier =
                Modifier
                    .width(46.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 5.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = time,
                style = RadarType.monoSmall,
                color = RadarColors.ink,
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "#${entity.id}",
                style = RadarType.monoMicro,
                color = RadarColors.ink3,
                textAlign = TextAlign.End,
            )
        }

        DottedRuleVertical()

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            // Line 1: channel glyph + source + classification (+ amount / code).
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(channelGlyph(entity.source), RadarColors.ink2, 12.dp)
                Spacer(Modifier.width(6.dp))
                Chip(
                    text = category?.label ?: entity.category,
                    color = category?.color?.let(::hexColor) ?: RadarColors.ruleSoft,
                )
                amount?.let { value ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${Chars.RUPEE}${"%,.0f".format(value)}",
                        style = RadarType.microBold,
                        color = RadarColors.ink,
                    )
                }
                code?.let { value ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "code $value",
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))

            Text(
                text = title,
                style = RadarType.serifTitle,
                color = RadarColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entity.content,
                style = RadarType.small,
                color = RadarColors.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            DottedRule()
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "DELIVERY ", style = RadarType.micro, color = RadarColors.ink3)
                    Text(
                        text = deliveryFor(entity.type),
                        style = RadarType.microBold,
                        color = RadarColors.ink,
                    )
                }
                // State belongs with delivery, not with identity.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saved) {
                        // The saved mark sits with state, where the swipe acts.
                        GlyphIcon(glyph = Glyph.BookmarkFilled, tint = CategoryColors.Teal, size = 12.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Chip(text = stateLabel(entity.type), color = stateColor(entity.type))
                    Spacer(Modifier.width(6.dp))
                    Text(text = time, style = RadarType.microPlain, color = RadarColors.ink3)
                }
            }
        }
    }
    HardRule()
}

/** The saved filter has no rows yet: say how to make one. */
@Composable
private fun EmptySaved(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "NO SAVED MESSAGES", style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Swipe a message left to save it",
                style = RadarType.small,
                color = RadarColors.ink3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Alerts has no matches to show: either no rule has fired, or none exists yet.
 * Still the genuine empty state — [AlertsListBody] renders it only when the
 * store returns a page with no rows.
 */
@Composable
internal fun AlertsEmpty(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "NO ALERTS YET", style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Create a rule from a message",
                style = RadarType.small,
                color = RadarColors.ink3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Search results, rendered with the same [MessageRow] as the stream and with no
 * day headers: results are ordered by textual match, not by time, so a date
 * separator would be a lie. The summary and empty lines come from
 * [messagesSearchSummary] / [messagesSearchEmpty], which are tested without
 * rendering.
 */
@Composable
private fun SearchResults(
    query: String,
    results: List<EventEntity>,
    savedIds: Set<String>,
    listState: LazyListState,
    taxonomy: CategoryTaxonomy?,
    onOpenConversation: (String) -> Unit,
    onToggleSave: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (results.isEmpty()) {
        EmptySearch(query, Modifier.fillMaxSize())
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(RadarColors.paper3)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = messagesSearchSummary(query, results.size),
                    style = RadarType.micro,
                    color = RadarColors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HardRule()
        }
        items(items = results, key = { "s-${it.id}" }) { event ->
            // A result is a message, so it gets the same swipe and the same
            // actions as the stream — not a read-only copy of the row.
            SwipeableMessageRow(
                entity = event,
                saved = event.ulid in savedIds,
                taxonomy = taxonomy,
                onOpen = { onOpenConversation(event.title) },
                onToggleSave = { onToggleSave(event.ulid) },
                onDelete = { onDelete(event.ulid) },
            )
        }
        item { FooterStrip("${results.size} shown ${Chars.MIDDLE_DOT} messages only") }
    }
}

@Composable
private fun EmptySearch(
    query: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = messagesSearchEmpty(query),
            style = RadarType.micro,
            color = RadarColors.ink3,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- mapping

private fun channelGlyph(source: String): Glyph =
    when (source) {
        "sms", "mms" -> Glyph.Chat
        "mail", "email" -> Glyph.Mail
        "rss", "news", "feed" -> Glyph.Rss
        "weather" -> Glyph.CloudRain
        "radio" -> Glyph.Broadcast
        else -> Glyph.Stack
    }

/** Delivery state (rule-driven later). */
private fun stateLabel(type: String): String =
    when (type) {
        "inbox" -> "Fired"
        "sent", "queued", "outbox" -> "Queued"
        else -> "Muted"
    }

private fun stateColor(type: String): Color =
    when (type) {
        "inbox" -> CategoryColors.Vermilion
        "sent", "queued", "outbox" -> CategoryColors.Chartreuse
        else -> RadarColors.paper4
    }

/** A stored `#RRGGBB` as an opaque Compose colour; bad input falls back to ink. */
private fun hexColor(hex: String): Color {
    val rgb = hex.removePrefix("#").toLongOrNull(16) ?: return RadarColors.ruleSoft
    return Color(0xFF000000L or rgb)
}

private fun deliveryFor(type: String): String =
    when (type) {
        "inbox" -> "Push ${Chars.MIDDLE_DOT} now"
        "sent" -> "Sent"
        "queued", "outbox" -> "Queued ${Chars.MIDDLE_DOT} retry"
        "failed" -> "Not delivered"
        "draft" -> "Draft"
        else -> "In-app"
    }
