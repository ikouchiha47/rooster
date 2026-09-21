package com.personalos.app.ui.travel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.tag.Tags
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.TaggedEvent
import com.personalos.app.ui.calendar.CALENDAR_MONTHS_SHOWN
import com.personalos.app.ui.calendar.CalendarMonth
import com.personalos.app.ui.calendar.ObservanceRow
import com.personalos.app.ui.calendar.calendarMonthHeader
import com.personalos.app.ui.calendar.groupIntoCalendarMonths
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SectionHeader
import com.personalos.app.ui.common.TabSpec
import com.personalos.app.ui.common.TabStrip
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.news.NewsRow
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 40

/**
 * Travel: the dates that constrain a trip, and what is happening around them.
 *
 * Two tabs, two reads — both owned elsewhere. Calendar reads the same
 * [com.personalos.app.data.CalendarRepository] and the same month-grouping model
 * as the Calendar tile, so the two cannot disagree. News reads travel-tagged
 * items through the same `pageByTagItems` read the News tile uses and renders
 * them with the same row.
 *
 * Plans, the leave optimiser and season windows are deliberately out of scope
 * (PRD 0001 §4, v3) and are not built here.
 */
@Composable
fun TravelScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Destination) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val database = remember { AppDatabase.getInstance(context) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tab = travelTabAt(selectedTab)

    val regions by container.calendarRepository.followedRegions().collectAsStateWithLifecycle(initialValue = emptyList())
    val upcoming by container.calendarRepository.upcoming().collectAsStateWithLifecycle(initialValue = emptyList())
    val months = remember(upcoming) { groupIntoCalendarMonths(upcoming) }
    val newsCount by database.eventDao().observeCountByTag(Tags.TRAVEL).collectAsStateWithLifecycle(initialValue = 0)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper2),
    ) {
        RadarHeader(
            title = "Travel",
            accent = CategoryColors.Mustard,
            onBack = onBack,
        )
        TabStrip(
            items =
                TravelTab.entries.map { spec ->
                    TabSpec(spec.label, if (spec.loadsObservances()) months.size else newsCount)
                },
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
        )

        when (tab) {
            TravelTab.CALENDAR ->
                CalendarTab(
                    regions = regions,
                    months = months,
                    modifier = Modifier.weight(1f),
                )

            TravelTab.NEWS ->
                TravelNewsTab(
                    onNavigate = onNavigate,
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

@Composable
private fun CalendarTab(
    regions: List<String>,
    months: List<CalendarMonth>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize()) {
        item { WidgetHeader(title = "Upcoming", note = "${months.size} months") }

        if (months.isEmpty()) {
            item {
                Text(
                    text =
                        if (regions.isEmpty()) {
                            "No regions followed yet — add one in the Calendar tile."
                        } else {
                            "Nothing upcoming in the next $CALENDAR_MONTHS_SHOWN months."
                        },
                    style = RadarType.body,
                    color = RadarColors.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        } else {
            months.forEach { month ->
                item(key = "month-${month.year}-${month.month}") {
                    SectionHeader(
                        title = calendarMonthHeader(month.year, month.month),
                        accent = CategoryColors.Mustard,
                        note = "${month.items.size}",
                    )
                }
                items(month.items, key = { "${it.source}:${it.feedUid}" }) { row ->
                    ObservanceRow(row)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TravelNewsTab(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember { AppDatabase.getInstance(context) }
    val dao = remember { database.eventDao() }
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<TaggedEvent>>(emptyList()) }
    var cursorTs by remember { mutableStateOf<Long?>(null) }
    var cursorId by remember { mutableStateOf(0L) }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun loadMore(reset: Boolean) {
        if (loading || (!reset && endReached)) return
        loading = true
        try {
            val page =
                dao.pageByTagItems(
                    Tags.TRAVEL,
                    if (reset) null else cursorTs,
                    if (reset) 0L else cursorId,
                    PAGE_SIZE,
                )
            val last = page.lastOrNull()
            rows = if (reset) page else rows + page
            cursorTs = last?.event?.timestamp ?: cursorTs
            cursorId = last?.event?.id ?: cursorId
            endReached = page.size < PAGE_SIZE
            loaded = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadMore(reset = true) }

    LazyColumn(modifier.fillMaxSize()) {
        item { WidgetHeader(title = "Travel news", note = "${rows.size}") }

        if (loaded && rows.isEmpty()) {
            item {
                Text(
                    text = "No travel-tagged news yet.",
                    style = RadarType.body,
                    color = RadarColors.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }

        items(rows, key = { "event:${it.event.id}" }) { row ->
            NewsRow(
                event = row.event.toDomain(),
                tags = row.tagList,
                onOpen = { onNavigate(Destination.Article(row.event.id)) },
            )
        }

        if (rows.isNotEmpty() && !endReached) {
            item {
                Text(
                    text = if (loading) "LOADING…" else "LOAD MORE",
                    style = RadarType.labelMicro,
                    color = RadarColors.ink2,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !loading) { scope.launch { loadMore(reset = false) } }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
    }
}
