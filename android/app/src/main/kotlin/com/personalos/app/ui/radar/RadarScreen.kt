package com.personalos.app.ui.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.SmsClass
import com.personalos.app.core.SmsClassifier
import com.personalos.app.core.feed.FeedCategories
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.EventDao
import com.personalos.app.data.EventEntity
import com.personalos.app.ui.common.CategorySpine
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.DayMarker
import com.personalos.app.ui.common.DottedRule
import com.personalos.app.ui.common.DottedRuleVertical
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.GlanceCell
import com.personalos.app.ui.common.GlanceGrid
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphActionButton
import com.personalos.app.ui.common.GlyphIcon
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarAppBar
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SectionHeader
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

/**
 * The default (unfiltered) stream is deliberately small: a curated handful so no
 * single source can flood the timeline. Larger volumes are opt-in via a filter
 * tab now, and via user rules later.
 */
private const val CONTROLLED_CAP = 12
private const val PER_SOURCE_CAP = 6

private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

private sealed interface RadarRow {
    data class Header(
        val dayStart: Long,
    ) : RadarRow

    data class Item(
        val event: EventEntity,
    ) : RadarRow
}

private val RADAR_TABS: List<Pair<String, String?>> =
    listOf(
        "All" to null,
        "Incidents" to FeedCategories.INCIDENT,
        "News" to FeedCategories.NEWS,
        "Money" to SmsClass.TRANSACTION.name,
        "Travel" to SmsClass.TRAVEL_LOGISTICS.name,
        "Bills" to SmsClass.BILL.name,
        "Promo" to SmsClass.PROMOTIONAL.name,
        "Updates" to SmsClass.UPDATES.name,
    )

@Composable
fun RadarScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val database = remember { AppDatabase.getInstance(context) }
    val dao = database.eventDao()
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val category = RADAR_TABS[selectedTab].second

    // Pull feeds once when the screen opens; cached, so repeat opens are cheap.
    LaunchedEffect(Unit) { container.feeds.refresh() }

    val listState = rememberLazyListState()
    var items by remember { mutableStateOf<List<EventEntity>>(emptyList()) }
    var cursorTs by remember { mutableStateOf<Long?>(null) }
    var cursorId by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }

    // Reload the first page when new events land, so the list is live.
    val maxId by dao.observeMaxId().collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(category, maxId) {
        cursorTs = null
        cursorId = 0L
        endReached = false
        loading = true
        val first = dao.radarPage(category, null, 0L, PAGE_SIZE)
        items = first
        if (first.isNotEmpty()) {
            cursorTs = first.last().timestamp
            cursorId = first.last().id
        }
        if (first.size < PAGE_SIZE) endReached = true
        loading = false
    }

    // Page only when the user has chosen a filter (i.e. asked for volume).
    LaunchedEffect(listState, category) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            if (category == null) return@collect
            if (!loading && !endReached && items.isNotEmpty() && lastVisible >= items.size - 5) {
                loading = true
                val next = dao.radarPage(category, cursorTs, cursorId, PAGE_SIZE)
                if (next.isEmpty()) {
                    endReached = true
                } else {
                    items = items + next
                    cursorTs = next.last().timestamp
                    cursorId = next.last().id
                    if (next.size < PAGE_SIZE) endReached = true
                }
                loading = false
            }
        }
    }

    // Curated default: cap the total and cap each source, so news cannot bury SMS.
    val visible =
        remember(items, category) {
            if (category != null) items else curate(items)
        }

    val rows =
        remember(visible) {
            val out = ArrayList<RadarRow>(visible.size + 8)
            var currentBucket = Long.MIN_VALUE
            for (e in visible) {
                val bucket = e.timestamp / DAY_MS
                if (bucket != currentBucket) {
                    currentBucket = bucket
                    out.add(RadarRow.Header(e.timestamp))
                }
                out.add(RadarRow.Item(e))
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
            RadarAppBar(
                title = "Personal Radar",
                sub = "Sources live ${Chars.MIDDLE_DOT} controlled view",
                mark = true,
                actions = {
                    GlyphActionButton(Glyph.Search, onClick = { })
                    GlyphActionButton(
                        Glyph.Refresh,
                        onClick = { scope.launch { container.feeds.refresh() } },
                    )
                },
            )

            StatusStrip(dao)
            SyncLine(
                left = "Sync on open ${Chars.MIDDLE_DOT} cached 1h",
                right = if (category == null) "curated" else "filtered",
            )
            RadarTabs(dao, selectedTab, onSelect = { selectedTab = it })
            FilterRail()
            RadarGlance(dao)

            TimelineHeader(dao, category, shown = visible.size)

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                if (rows.isEmpty()) {
                    EmptyRadar(Modifier.fillMaxSize())
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(
                            items = rows,
                            key = { row ->
                                when (row) {
                                    is RadarRow.Header -> "d-${row.dayStart}"
                                    is RadarRow.Item -> "e-${row.event.id}"
                                }
                            },
                        ) { row ->
                            when (row) {
                                is RadarRow.Header -> {
                                    val (label, right) =
                                        remember(row.dayStart) {
                                            val l = dayLabel(row.dayStart)
                                            l to dayLabelRight(l, row.dayStart)
                                        }
                                    DayMarker(label = label, right = right)
                                }

                                is RadarRow.Item -> RadarEventRow(row.event)
                            }
                        }
                        item {
                            FooterStrip(
                                if (category == null) {
                                    "Curated view ${Chars.MIDDLE_DOT} pick a tab, or add a rule, for more"
                                } else {
                                    "End of cached events"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Caps the total and each source, newest first. */
private fun curate(all: List<EventEntity>): List<EventEntity> {
    val perSource = HashMap<String, Int>()
    val out = ArrayList<EventEntity>(CONTROLLED_CAP)
    for (event in all) {
        if (out.size >= CONTROLLED_CAP) break
        val used = perSource[event.source] ?: 0
        if (used >= PER_SOURCE_CAP) continue
        perSource[event.source] = used + 1
        out.add(event)
    }
    return out
}

// ------------------------------------------------------------------- chrome

@Composable
private fun StatusStrip(dao: EventDao) {
    val since = remember { System.currentTimeMillis() - DAY_MS }
    val events by dao.observeCountAllSince(since).collectAsStateWithLifecycle(initialValue = 0)
    val alerts by dao.observeCountSince("inbox", since).collectAsStateWithLifecycle(initialValue = 0)
    val sources by dao.observeSourceCount().collectAsStateWithLifecycle(initialValue = 0)
    val feeds by dao.observeFeedSourceCount().collectAsStateWithLifecycle(initialValue = 0)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RadarColors.paper3)
                .border(1.dp, RadarColors.ink)
                .height(STAT_STRIP_HEIGHT),
    ) {
        StatColumn("Events 24h", pad(events), Modifier.weight(1f))
        Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
        StatColumn("Alerts", pad(alerts), Modifier.weight(1f), CategoryColors.Vermilion)
        Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
        StatColumn("Sources", pad(sources), Modifier.weight(1f))
        Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
        StatColumn("Feeds OK", pad(feeds), Modifier.weight(1f), CategoryColors.Teal)
    }
}

@Composable
private fun StatColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = RadarColors.ink,
) {
    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(
            text = value,
            style = RadarType.monoH2.copy(fontSize = 20.sp),
            color = valueColor,
            maxLines = 1,
        )
        Text(
            text = label.uppercase(),
            style = RadarType.micro,
            color = RadarColors.ink3,
            maxLines = 1,
        )
    }
}

@Composable
private fun RadarTabs(
    dao: EventDao,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val counts =
        RADAR_TABS.map { (_, category) ->
            dao.observeCountByCategory(category).collectAsStateWithLifecycle(initialValue = 0)
        }
    TabStrip(
        items = RADAR_TABS.mapIndexed { i, (label, _) -> TabSpec(label, counts[i].value) },
        selectedIndex = selectedIndex,
        onSelect = onSelect,
    )
}

@Composable
private fun FilterRail() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "SORT", style = RadarType.micro, color = RadarColors.ink3)
        Chip(text = "Newest", color = RadarColors.ink2)
        Text(text = "REGION", style = RadarType.micro, color = RadarColors.ink3)
        Chip(text = "IN ${Chars.MIDDLE_DOT} Kolkata", color = CategoryColors.Indigo)
        Text(text = "DEDUPE", style = RadarType.micro, color = RadarColors.ink3)
        Chip(text = "On", color = CategoryColors.Teal)
    }
}

@Composable
private fun TimelineHeader(
    dao: EventDao,
    category: String?,
    shown: Int,
) {
    val total by dao.observeCountByCategory(category).collectAsStateWithLifecycle(initialValue = 0)
    SectionHeader(
        title = "Timeline",
        accent = CategoryColors.Vermilion,
        note = "$shown of $total",
        onInk = true,
    )
}

@Composable
private fun RadarGlance(dao: EventDao) {
    val news by dao
        .observeCountByCategory(FeedCategories.NEWS)
        .collectAsStateWithLifecycle(initialValue = 0)
    val incidents by dao
        .observeCountByCategory(FeedCategories.INCIDENT)
        .collectAsStateWithLifecycle(initialValue = 0)
    val container = LocalAppContainer.current
    val fxFlow = remember(container) { container.fx.observe() }
    val rates by fxFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val glanceFlow = remember(container) { container.glance.observe() }
    val glance by glanceFlow.collectAsStateWithLifecycle(initialValue = null)

    Box(modifier = Modifier.padding(8.dp)) {
        GlanceGrid(
            title = "At a glance",
            note = "rolling 24h",
            cells =
                listOf(
                    GlanceCell(
                        label = "Money out",
                        value =
                            glance
                                ?.moneyOutToday
                                ?.let { "${Chars.RUPEE}${"%,.0f".format(it.amount)}" } ?: "n/a",
                        suffix = glance?.moneyOutToday?.note,
                    ),
                    GlanceCell(
                        label = "FX watch",
                        value = rates.firstOrNull()?.let { "%.2f".format(it.rate) } ?: "--",
                        suffix = rates.firstOrNull()?.pair,
                        valueColor = CategoryColors.Teal,
                    ),
                    GlanceCell(
                        label = "News matched",
                        value = pad(news),
                        suffix = "in feed",
                    ),
                    GlanceCell(
                        label = "Open incidents",
                        value = pad(incidents),
                        suffix = "status feeds",
                        valueColor = if (incidents > 0) CategoryColors.Rust else RadarColors.ink,
                    ),
                ),
        )
    }
}

// --------------------------------------------------------------------- rows

@Composable
private fun RadarEventRow(entity: EventEntity) {
    val time = remember(entity.timestamp) { TIME_FMT.format(Date(entity.timestamp)) }
    val classification =
        remember(entity.title, entity.content) {
            SmsClassifier.classify(entity.title, entity.content)
        }
    val amount = classification.amount
    val code = classification.code

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
    ) {
        CategorySpine(categoryColor(entity.category))

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(channelGlyph(entity.source), RadarColors.ink2, 12.dp)
                Spacer(Modifier.width(5.dp))
                Chip(text = categoryLabel(entity.category), color = categoryColor(entity.category))
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = entity.title,
                style = RadarType.serifTitle,
                color = RadarColors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entity.content.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entity.content,
                    style = RadarType.small,
                    color = RadarColors.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (amount != null || code != null) {
                Spacer(Modifier.height(4.dp))
                DottedRule()
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    amount?.let { MetaItem("AMT", "${Chars.RUPEE}${"%,.0f".format(it)}") }
                    code?.let { MetaItem("CODE", it) }
                }
            }
        }
    }
    HardRule()
}

@Composable
private fun MetaItem(
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = RadarType.micro, color = RadarColors.ink3)
        Spacer(Modifier.width(4.dp))
        Text(text = value, style = RadarType.microBold, color = RadarColors.ink)
    }
}

@Composable
private fun EmptyRadar(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "NO EVENTS", style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Grant SMS access, or add a feed, to fill the radar",
                style = RadarType.small,
                color = RadarColors.ink3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ------------------------------------------------------------------ mapping

private fun categoryLabel(category: String): String =
    when (category) {
        FeedCategories.NEWS -> "News"
        FeedCategories.INCIDENT -> "Incident"
        SmsClass.PERSONAL.name -> "Personal"
        SmsClass.OTP.name -> "OTP"
        SmsClass.TRANSACTION.name -> "Money"
        SmsClass.BILL.name -> "Bill"
        SmsClass.TRAVEL_LOGISTICS.name -> "Travel"
        SmsClass.UPDATES.name -> "Update"
        SmsClass.PROMOTIONAL.name -> "Promo"
        SmsClass.SPAM_FRAUD.name -> "Spam"
        else -> "Other"
    }

private fun categoryColor(category: String): Color =
    when (category) {
        FeedCategories.NEWS -> CategoryColors.Indigo
        FeedCategories.INCIDENT -> CategoryColors.Rust
        SmsClass.PERSONAL.name -> RadarColors.ink2
        SmsClass.OTP.name -> CategoryColors.Cyan
        SmsClass.TRANSACTION.name -> CategoryColors.Teal
        SmsClass.BILL.name -> CategoryColors.Mustard
        SmsClass.TRAVEL_LOGISTICS.name -> CategoryColors.Indigo
        SmsClass.UPDATES.name -> CategoryColors.Periwinkle
        SmsClass.PROMOTIONAL.name -> CategoryColors.Plum
        SmsClass.SPAM_FRAUD.name -> CategoryColors.Vermilion
        else -> RadarColors.ruleSoft
    }

private fun channelGlyph(source: String): Glyph =
    when {
        source.startsWith("rss") -> Glyph.Rss
        source == "sms" || source == "mms" -> Glyph.Chat
        source == "mail" || source == "email" -> Glyph.Mail
        source == "weather" -> Glyph.CloudRain
        source == "radio" -> Glyph.Broadcast
        else -> Glyph.Stack
    }

private fun pad(value: Int): String = value.toString().padStart(2, '0')

private val STAT_STRIP_HEIGHT = 56.dp
