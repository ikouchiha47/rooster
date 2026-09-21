package com.personalos.app.ui.messages

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.SmsClass
import com.personalos.app.core.SmsClassifier
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.EventDao
import com.personalos.app.data.EventEntity
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
import com.personalos.app.ui.common.SyncLine
import com.personalos.app.ui.common.TabSpec
import com.personalos.app.ui.common.TabStrip
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.common.dayLabelRight
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PAGE_SIZE = 200
private const val DAY_MS = 86_400_000L

// Hoisted formatter: created once, never per row.
private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

private sealed interface MsgRow {
    data class Header(
        val dayStart: Long,
    ) : MsgRow

    data class Item(
        val event: EventEntity,
    ) : MsgRow
}

@Composable
fun MessagesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val database = remember { AppDatabase.getInstance(context) }
    val dao = database.eventDao()

    var selectedTab by remember { mutableIntStateOf(0) }
    val mode = messagesTabAt(selectedTab).mode()

    val listState = rememberLazyListState()
    var items by remember { mutableStateOf<List<EventEntity>>(emptyList()) }
    var cursorTs by remember { mutableStateOf<Long?>(null) }
    var cursorId by remember { mutableStateOf(0L) }
    var savedCursor by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var savedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    // Cursor (keyset) pagination: first page on tab change.
    // Reload the first page when new events land, so the list is live.
    val maxId by dao.observeMaxId().collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(mode, maxId) {
        items = emptyList()
        cursorTs = null
        cursorId = 0L
        savedCursor = null
        endReached = false
        loading = true
        if (mode == null) {
            // Saved is not a box: it reads the save stamp, not the publish time.
            val first = container.bookmarks.saved(source = SmsSource.SOURCE_ID, limit = PAGE_SIZE)
            items = first
            savedCursor = first.lastOrNull()?.bookmarkedAt
            if (first.size < PAGE_SIZE) endReached = true
        } else {
            val first = dao.page(mode, null, 0L, PAGE_SIZE)
            items = first
            if (first.isNotEmpty()) {
                cursorTs = first.last().timestamp
                cursorId = first.last().id
            }
            if (first.size < PAGE_SIZE) endReached = true
        }
        loading = false
    }

    // Re-read the saved flags for the loaded page, so the glyph and the swipe
    // reflect the store rather than a guess made at render time.
    LaunchedEffect(items, mode) {
        savedIds =
            items
                .filter { it.bookmarkedAt != null }
                .map { it.ulid }
                .toSet()
    }

    // ...and the next page when the list nears its end.
    LaunchedEffect(listState, mode) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            if (!loading && !endReached && items.isNotEmpty() && lastVisible >= items.size - 5) {
                loading = true
                if (mode == null) {
                    val next = container.bookmarks.saved(SmsSource.SOURCE_ID, savedCursor, PAGE_SIZE)
                    if (next.isEmpty()) {
                        endReached = true
                    } else {
                        items = items + next
                        savedCursor = next.lastOrNull()?.bookmarkedAt
                        if (next.size < PAGE_SIZE) endReached = true
                    }
                } else {
                    val next = dao.page(mode, cursorTs, cursorId, PAGE_SIZE)
                    if (next.isEmpty()) {
                        endReached = true
                    } else {
                        items = items + next
                        cursorTs = next.last().timestamp
                        cursorId = next.last().id
                        if (next.size < PAGE_SIZE) endReached = true
                    }
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
                val bucket = e.timestamp / DAY_MS
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
            MessagesAppBar(dao)
            MessagesTabs(dao, selectedTab, onSelect = { selectedTab = it })
            SyncLine(
                left = "Push + in-app inbox",
                right = "Quiet hours 23:00${Chars.EN_DASH}07:00",
            )

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                if (rows.isEmpty()) {
                    EmptyInbox(Modifier.fillMaxSize())
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
                                        onToggleSave = {
                                            scope.launch {
                                                val nowSaved = container.bookmarks.toggle(row.event.ulid)
                                                savedIds =
                                                    if (nowSaved) {
                                                        savedIds + row.event.ulid
                                                    } else {
                                                        savedIds - row.event.ulid
                                                    }
                                                if (mode == null) {
                                                    // In the Saved view an unsaved item no longer belongs.
                                                    items = items.filterNot { it.ulid == row.event.ulid }
                                                }
                                            }
                                        },
                                    )
                            }
                        }
                        item { FooterStrip("Last refresh 09:44 ${Chars.MIDDLE_DOT} sample data") }
                    }
                }
            }
        }
    }
}

// Counts are collected inside their own composable, so a count change
// recomposes only the app bar / tab strip, not the list.
@Composable
private fun MessagesAppBar(dao: EventDao) {
    val unread by dao.observeCountByMode("inbox").collectAsStateWithLifecycle(initialValue = 0)
    RadarAppBar(
        title = "Messages",
        sub = "Notifications ${Chars.MIDDLE_DOT} $unread unread",
        actions = {
            GlyphActionButton(Glyph.Search, onClick = { })
            GlyphActionButton(Glyph.Dots, onClick = { })
        },
    )
}

@Composable
private fun MessagesTabs(
    dao: EventDao,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val container = LocalAppContainer.current
    val all by dao.observeCountByMode("all").collectAsStateWithLifecycle(initialValue = 0)
    val inbox by dao.observeCountByMode("inbox").collectAsStateWithLifecycle(initialValue = 0)
    val sent by dao.observeCountByMode("sent").collectAsStateWithLifecycle(initialValue = 0)
    val other by dao.observeCountByMode("other").collectAsStateWithLifecycle(initialValue = 0)
    val saved by container.bookmarks.observeSavedCount(SmsSource.SOURCE_ID).collectAsStateWithLifecycle(initialValue = 0)

    // Labels come from the tab model, so the strip and the loader cannot disagree.
    val counts = listOf(all, inbox, sent, other, saved)
    TabStrip(
        items = MessagesTab.entries.mapIndexed { index, tab -> TabSpec(tab.label, counts[index]) },
        selectedIndex = selectedIndex,
        onSelect = onSelect,
    )
}

/**
 * A message row with swipe-left to save or unsave.
 *
 * The swipe **acts and snaps back** rather than dismissing: the item is not
 * going away, its saved state is changing, and a row that vanished would look
 * like a delete. The revealed background states what the swipe will do.
 */
@Composable
private fun SwipeableMessageRow(
    entity: EventEntity,
    saved: Boolean,
    onToggleSave: () -> Unit,
) {
    val state =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) onToggleSave()
                // Never dismiss: report the gesture, then spring back.
                false
            },
        )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(RadarColors.paper3)
                        .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(
                    glyph = if (saved) Glyph.Stack else Glyph.Plus,
                    tint = RadarColors.ink,
                    size = 16.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (saved) "UNSAVE" else "SAVE",
                    style = RadarType.labelMicro,
                    color = RadarColors.ink,
                )
            }
        },
    ) {
        MessageRow(entity, saved = saved)
    }
}

@Composable
private fun MessageRow(
    entity: EventEntity,
    saved: Boolean = false,
) {
    val unread = entity.type == "inbox"
    val time = remember(entity.timestamp) { TIME_FMT.format(Date(entity.timestamp)) }
    // Classification drives the chip. It is a pure function, so it is safe to
    // run per visible row (LazyColumn keeps this to a handful at once).
    val classification =
        remember(entity.title, entity.content) {
            SmsClassifier.classify(entity.title, entity.content)
        }

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
                    text = labelFor(classification.klass),
                    color = colorFor(classification.klass),
                )
                classification.amount?.let { amount ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${Chars.RUPEE}${"%,.0f".format(amount)}",
                        style = RadarType.microBold,
                        color = RadarColors.ink,
                    )
                }
                classification.code?.let { code ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "code $code",
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))

            Text(
                text = entity.title,
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
                        GlyphIcon(glyph = Glyph.Stack, tint = CategoryColors.Teal, size = 12.dp)
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

@Composable
private fun EmptyInbox(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "NO NOTIFICATIONS", style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Grant SMS access to ingest messages",
                style = RadarType.small,
                color = RadarColors.ink3,
                textAlign = TextAlign.Center,
            )
        }
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

/** Short chip label per intent class. */
private fun labelFor(klass: SmsClass): String =
    when (klass) {
        SmsClass.PERSONAL -> "Personal"
        SmsClass.OTP -> "OTP"
        SmsClass.TRANSACTION -> "Money"
        SmsClass.BILL -> "Bill"
        SmsClass.TRAVEL_LOGISTICS -> "Travel"
        SmsClass.UPDATES -> "Update"
        SmsClass.PROMOTIONAL -> "Promo"
        SmsClass.SPAM_FRAUD -> "Spam"
    }

private fun colorFor(klass: SmsClass): Color =
    when (klass) {
        SmsClass.PERSONAL -> RadarColors.ink2
        SmsClass.OTP -> CategoryColors.Cyan
        SmsClass.TRANSACTION -> CategoryColors.Teal
        SmsClass.BILL -> CategoryColors.Mustard
        SmsClass.TRAVEL_LOGISTICS -> CategoryColors.Indigo
        SmsClass.UPDATES -> CategoryColors.Periwinkle
        SmsClass.PROMOTIONAL -> CategoryColors.Plum
        SmsClass.SPAM_FRAUD -> CategoryColors.Vermilion
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
