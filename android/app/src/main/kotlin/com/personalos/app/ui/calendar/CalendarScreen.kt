package com.personalos.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.calendar.CalendarDirectoryParser
import com.personalos.app.core.calendar.CalendarSurface
import com.personalos.app.core.tile.ActionKind
import com.personalos.app.core.tile.TileConfig
import com.personalos.app.core.time.UserZone
import com.personalos.app.data.CalendarDateEntity
import com.personalos.app.data.CalendarSource
import com.personalos.app.data.plannerOccurrences
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.HeaderMeta
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.TabSpec
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import com.personalos.app.ui.tile.TileScaffold
import com.personalos.app.ui.tile.destinationForAction
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CALENDAR_TILE =
    TileConfig(
        id = "calendar",
        title = "Calendar",
        searchHint = "Search calendar",
        actions = listOf(ActionKind.SYNC, ActionKind.SETTINGS),
    )

private val DAY_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** `12 MAR`, or the raw ISO string rather than nothing if it cannot be parsed. */
private fun observanceDay(iso: String): String = runCatching { LocalDate.parse(iso).format(DAY_FMT) }.getOrDefault(iso)

/**
 * Calendar: month/day/year over the same namespaces, with a per-surface saved
 * set and a quick filter. The grid/agenda/selection decisions are pure and
 * tested without rendering.
 */
@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Destination) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val sources by container.sourceRepository.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val calendars by container.calendarRepository.calendars(CalendarSurface.CALENDAR).collectAsStateWithLifecycle(initialValue = emptyList())
    val upcoming by container.calendarRepository.upcoming(CalendarSurface.CALENDAR).collectAsStateWithLifecycle(initialValue = emptyList())
    val regionLabels = remember(sources) { calendarRegionLabels(sources) }
    val plannerItems by container.plannerRepository.allItems().collectAsStateWithLifecycle(initialValue = emptyList())
    val plannerTags by container.plannerRepository.tags.collectAsStateWithLifecycle(initialValue = emptyList())
    val plannerWorkspaces by container.plannerRepository.workspaces.collectAsStateWithLifecycle(initialValue = emptyList())

    var mode by remember { mutableStateOf(CalendarMode.MONTH) }
    var namespaceMenuOpen by remember { mutableStateOf(false) }

    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    val listState = rememberLazyListState()

    val plannerCounts =
        remember(plannerItems, month) {
            val from = month.atDay(1)
            val to = month.atEndOfMonth()
            plannerItems
                .flatMap { item -> plannerOccurrences(item.item, from, to, UserZone.current) }
                .groupingBy { it.date }
                .eachCount()
        }
    val cells =
        remember(month, upcoming, selected, today, plannerCounts) {
            calendarGrid(month, upcoming, selected, today, plannerCounts)
        }
    val agenda = remember(upcoming, selected) { agendaFor(upcoming, selected) }
    val year = remember(month, upcoming) { yearMonths(month.year, upcoming) }
    val plannerForDay =
        remember(plannerItems, selected) {
            plannerItems
                .flatMap { item ->
                    plannerOccurrences(item.item, selected, selected, UserZone.current)
                        .map { PlannerEntry(it, item.tagIds) }
                }.sortedBy { it.occurrence.start ?: java.time.LocalDateTime.MIN }
        }

    LaunchedEffect(mode) {
        if (mode == CalendarMode.DAY) {
            // Open the timeline at 08:00 rather than at midnight. The base is
            // the number of header items before the slots (filter, day nav,
            // week strip, all-day, timeline header).
            listState.scrollToItem(5 + 16)
        } else {
            listState.scrollToItem(0)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    scope.launch {
                        listState.scrollToItem(if (mode == CalendarMode.DAY) 5 + 16 else 0)
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    TileScaffold(
        config = CALENDAR_TILE,
        modifier = modifier,
        accent = CategoryColors.Rust,
        onBack = onBack,
        showSearch = false,
        header = {
            HeaderMeta(
                primary = calendarCountLabel(calendars.count { it.enabled }),
                secondary = "${compactNumber(upcoming.size)} UPCOMING",
            )
        },
        tabs =
            listOf(
                TabSpec("Day", agenda.size),
                TabSpec("Month", upcoming.size),
                TabSpec("Year", 12),
            ),
        selectedTab = mode.ordinal,
        onSelectTab = { mode = CalendarMode.entries[it] },
        onAction = { action ->
            when (action) {
                ActionKind.SYNC ->
                    scope.launch {
                        container.sync.run("calendar") {
                            container.sync.step("refreshing calendars")
                            runCatching { container.feeds.refresh(force = true) }
                            container.sync.step("done")
                        }
                    }

                ActionKind.SETTINGS -> onNavigate(Destination.CalendarSettings)
                else -> destinationForAction(action)?.let(onNavigate)
            }
        },
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                CalendarFilterBar(
                    calendars = calendars,
                    expanded = namespaceMenuOpen,
                    onExpandedChange = { namespaceMenuOpen = it },
                    onToggle = { source, enabled ->
                        scope.launch {
                            container.calendarRepository.setEnabled(CalendarSurface.CALENDAR, source, enabled)
                        }
                    },
                    onAll = {
                        scope.launch { container.calendarRepository.reset(CalendarSurface.CALENDAR) }
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlatButton(
                        text = "Add reminder",
                        onClick = { onNavigate(Destination.PlannerEditor(selected.toString(), "reminder")) },
                    )
                    Spacer(Modifier.width(5.dp))
                    FlatButton(
                        text = "Add event",
                        onClick = { onNavigate(Destination.PlannerEditor(selected.toString(), "event")) },
                        solid = true,
                    )
                }
            }

            when (mode) {
                CalendarMode.MONTH -> {
                    item {
                        MonthNavigation(
                            month = month,
                            onPrev = {
                                val (next, day) = shiftMonth(month, selected, -1)
                                month = next
                                selected = day
                            },
                            onNext = {
                                val (next, day) = shiftMonth(month, selected, 1)
                                month = next
                                selected = day
                            },
                            onToday = {
                                month = YearMonth.from(today)
                                selected = today
                            },
                        )
                    }
                    item { WeekdayHeader() }
                    item { MonthGrid(cells = cells, onSelect = { selected = it }) }
                    item { AgendaHeader(date = selected, count = agenda.size + plannerForDay.size) }
                    item {
                        PlannerSection(
                            entries = plannerForDay,
                            tagLabels = plannerTags.associate { it.id to it.label },
                            workspaceNames = plannerWorkspaces.associate { it.id to it.name },
                            onOpen = { item ->
                                onNavigate(
                                    Destination.PlannerEditor(
                                        date = selected.toString(),
                                        kind = item.occurrence.item.kind,
                                        itemId = item.occurrence.item.id,
                                    ),
                                )
                            },
                        )
                    }
                    if (agenda.isEmpty() && plannerForDay.isEmpty()) {
                        item {
                            Text(
                                text =
                                    when {
                                        calendars.isEmpty() -> calendarEmptyMessage(0)
                                        calendars.none { it.enabled } -> "No namespaces selected — choose one in Namespaces."
                                        else -> "Nothing on this date."
                                    },
                                style = RadarType.body,
                                color = RadarColors.ink3,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        agendaSections(agenda, regionLabels)
                    }
                }

                CalendarMode.DAY -> {
                    item {
                        DayNavigator(
                            date = selected,
                            onPrev = {
                                val next = selected.minusDays(1)
                                selected = next
                                month = YearMonth.from(next)
                            },
                            onNext = {
                                val next = selected.plusDays(1)
                                selected = next
                                month = YearMonth.from(next)
                            },
                            onToday = {
                                selected = today
                                month = YearMonth.from(today)
                            },
                        )
                    }
                    item {
                        WeekStrip(
                            selected = selected,
                            today = today,
                            onSelect = {
                                selected = it
                                month = YearMonth.from(it)
                            },
                        )
                    }
                    item {
                        AllDaySection(
                            rows = agenda,
                            regionLabels = regionLabels,
                        )
                    }
                    item {
                        PlannerSection(
                            entries = plannerForDay,
                            tagLabels = plannerTags.associate { it.id to it.label },
                            workspaceNames = plannerWorkspaces.associate { it.id to it.name },
                            onOpen = { item ->
                                onNavigate(
                                    Destination.PlannerEditor(
                                        date = selected.toString(),
                                        kind = item.occurrence.item.kind,
                                        itemId = item.occurrence.item.id,
                                    ),
                                )
                            },
                        )
                    }
                    item { TimelineHeader() }
                    items(daySlots(LocalTime.now()), key = { "slot:${it.startMinute}" }) { slot ->
                        TimelineSlot(slot, plannerForDay)
                    }
                }

                CalendarMode.YEAR -> {
                    item {
                        YearNavigation(
                            year = month.year,
                            onPrev = { month = month.minusYears(1) },
                            onNext = { month = month.plusYears(1) },
                        )
                    }
                    item {
                        YearView(
                            months = year,
                            onOpen = { target ->
                                month = target
                                selected = target.atDay(1)
                                mode = CalendarMode.MONTH
                            },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CalendarFilterBar(
    calendars: List<CalendarSource>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onAll: () -> Unit,
) {
    val selected = calendars.filter { it.enabled }
    val label =
        when {
            calendars.isEmpty() -> "NONE"
            selected.size == calendars.size -> "ALL"
            selected.size == 1 -> selected.single().label
            else -> "${selected.size} SELECTED"
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "NAMESPACES", style = RadarType.labelMicro, color = RadarColors.ink3)
        Spacer(Modifier.width(6.dp))
        Box {
            Row(
                modifier =
                    Modifier
                        .border(1.dp, RadarColors.ruleSoft, RoundedCornerShape(2.dp))
                        .background(RadarColors.paper2)
                        .clickable(onClickLabel = "Choose namespaces") { onExpandedChange(true) }
                        .padding(start = 7.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label.uppercase(),
                    style = RadarType.labelMicro,
                    color = RadarColors.ink,
                    maxLines = 1,
                )
                Spacer(Modifier.width(5.dp))
                Text(text = "▾", style = RadarType.labelMicro, color = RadarColors.ink2)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier.background(RadarColors.paper),
            ) {
                DropdownMenuItem(
                    text = {
                        NamespaceOption(
                            label = "ALL",
                            checked = selected.size == calendars.size && calendars.isNotEmpty(),
                        )
                    },
                    onClick = { onAll() },
                )
                calendars.forEach { calendar ->
                    DropdownMenuItem(
                        text = { NamespaceOption(label = calendar.label, checked = calendar.enabled) },
                        onClick = { onToggle(calendar.id, !calendar.enabled) },
                    )
                }
            }
        }
    }
    SoftRule()
}

@Composable
private fun NamespaceOption(
    label: String,
    checked: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .width(13.dp)
                    .height(13.dp)
                    .border(1.dp, RadarColors.ink)
                    .background(if (checked) RadarColors.ink else RadarColors.paper2),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(text = "✓", style = RadarType.labelMicro, color = RadarColors.paper2)
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = label.uppercase(),
            style = RadarType.label,
            color = if (checked) RadarColors.ink else RadarColors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CenteredNavHeader(
    title: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "‹",
                style = RadarType.serifH3,
                color = RadarColors.ink,
                modifier = Modifier.clickable(onClickLabel = "Previous", onClick = onPrev).padding(horizontal = 4.dp),
            )
            Text(
                text = title,
                style = RadarType.serifH3,
                color = RadarColors.ink,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                text = "›",
                style = RadarType.serifH3,
                color = RadarColors.ink,
                modifier = Modifier.clickable(onClickLabel = "Next", onClick = onNext).padding(horizontal = 4.dp),
            )
        }
        if (onToday != null) {
            Text(
                text = "TODAY",
                style = RadarType.labelMicro,
                color = CategoryColors.Rust,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .border(1.dp, CategoryColors.Rust)
                        .clickable(onClick = onToday)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
    SoftRule()
}

@Composable
private fun MonthNavigation(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    CenteredNavHeader(
        title = monthTitle(month),
        onPrev = onPrev,
        onNext = onNext,
        onToday = onToday,
    )
}

@Composable
private fun YearNavigation(
    year: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    CenteredNavHeader(title = year.toString(), onPrev = onPrev, onNext = onNext)
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth().background(RadarColors.paper3)) {
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { day ->
            Text(
                text = day,
                style = RadarType.labelMicro,
                color = RadarColors.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(vertical = 3.dp),
            )
        }
    }
    SoftRule()
}

@Composable
private fun MonthGrid(
    cells: List<CalendarCell>,
    onSelect: (LocalDate) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCell(cell = cell, onClick = { onSelect(cell.date) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    cell: CalendarCell,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background =
        when {
            cell.isSelected -> RadarColors.paper3
            !cell.inMonth -> RadarColors.paper3
            else -> RadarColors.paper2
        }
    val numberColor = if (cell.inMonth) RadarColors.ink else RadarColors.ink3

    Column(
        modifier =
            modifier
                .height(58.dp)
                .background(background)
                .border(1.dp, RadarColors.ruleSoft)
                .clickable(onClick = onClick)
                .padding(3.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .width(18.dp)
                    .background(if (cell.isToday) RadarColors.ink else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = RadarType.monoSmall,
                color = if (cell.isToday) RadarColors.paper2 else numberColor,
                textDecoration = if (cell.items.isNotEmpty()) TextDecoration.Underline else null,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        cell.items.take(3).forEach { row ->
            Box(Modifier.fillMaxWidth(0.72f).height(2.dp).background(kindColor(row.kind)))
            Spacer(Modifier.height(2.dp))
        }
        if (cell.items.size > 3) {
            Text(
                text = "+${cell.items.size - 3}",
                style = RadarType.labelMicro,
                color = RadarColors.ink3,
                maxLines = 1,
            )
        }
        if (cell.plannerCount > 0) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(5.dp).height(5.dp).background(RadarColors.ink))
        }
    }
}

@Composable
private fun YearView(
    months: List<MiniMonth>,
    onOpen: (YearMonth) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        months.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { mini ->
                    MiniMonthView(
                        mini = mini,
                        onOpen = { onOpen(mini.month) },
                        modifier = Modifier.weight(1f).padding(2.dp),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MiniMonthView(
    mini: MiniMonth,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .border(1.dp, RadarColors.ink)
                .clickable(onClick = onOpen)
                .padding(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text =
                    mini.month.month.name
                        .take(3),
                style = RadarType.labelMicro,
                color = RadarColors.ink,
            )
            Spacer(Modifier.weight(1f))
            Text(text = compactNumber(mini.count), style = RadarType.labelMicro, color = RadarColors.ink3)
        }
        Spacer(Modifier.height(2.dp))
        val grid = calendarGrid(mini.month, mini.items, LocalDate.MIN, LocalDate.MIN)
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    Text(
                        text = if (cell.inMonth) cell.date.dayOfMonth.toString() else "",
                        style = RadarType.labelMicro,
                        color = if (cell.items.isNotEmpty()) CategoryColors.Rust else RadarColors.ink2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Text(
            text = mini.first?.let { "${observanceDay(it.date)} · ${it.name}" } ?: "No dates",
            style = RadarType.labelMicro,
            color = RadarColors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun AgendaHeader(
    date: LocalDate,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(RadarColors.paper3).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = observanceDay(date.toString()).uppercase(Locale.ENGLISH),
            style = RadarType.label,
            color = RadarColors.ink,
        )
        Spacer(Modifier.weight(1f))
        Text(text = compactNumber(count), style = RadarType.microPlain, color = RadarColors.ink3)
    }
    SoftRule()
}

@Composable
private fun DayNavigator(
    date: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    CenteredNavHeader(
        title = observanceDay(date.toString()),
        onPrev = onPrev,
        onNext = onNext,
        onToday = onToday,
    )
}

@Composable
private fun WeekStrip(
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(RadarColors.paper3)) {
        weekDays(selected).forEach { date ->
            val active = date == selected
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(date) }
                        .background(if (active) CategoryColors.Rust else Color.Transparent)
                        .border(1.dp, if (date == today) RadarColors.ink else Color.Transparent)
                        .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = weekDayLetter(date),
                    style = RadarType.labelMicro,
                    color = if (active) RadarColors.paper2 else RadarColors.ink3,
                )
                Text(
                    text = date.dayOfMonth.toString(),
                    style = RadarType.monoSmall,
                    color = if (active) RadarColors.paper2 else RadarColors.ink,
                )
            }
        }
    }
    SoftRule()
}

@Composable
private fun AllDaySection(
    rows: List<CalendarDateEntity>,
    regionLabels: Map<String, String>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(RadarColors.paper3).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "ALL DAY", style = RadarType.label, color = RadarColors.ink)
        Spacer(Modifier.weight(1f))
        Text(text = compactNumber(rows.size), style = RadarType.microPlain, color = RadarColors.ink3)
    }
    SoftRule()
    if (rows.isEmpty()) {
        Text(
            text = "No all-day items.",
            style = RadarType.microPlain,
            color = RadarColors.ink3,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    } else {
        agendaGroups(rows).forEach { group ->
            AgendaNamespaceHeader(
                label = regionLabels[group.source] ?: group.source,
                count = group.rows.size,
            )
            group.rows.forEach { row ->
                ObservanceRow(
                    row = row,
                    regionLabel = regionLabels[row.source],
                    showDate = false,
                    showRegion = false,
                )
            }
        }
    }
}

@Composable
private fun TimelineHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().background(RadarColors.paper3).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "TIMELINE", style = RadarType.label, color = RadarColors.ink)
        Spacer(Modifier.weight(1f))
        Text(text = "30 MIN", style = RadarType.labelMicro, color = RadarColors.ink3)
    }
    SoftRule()
}

@Composable
private fun TimelineSlot(
    slot: DaySlot,
    entries: List<PlannerEntry>,
) {
    val timed =
        entries.filter { entry ->
            val start = entry.occurrence.start ?: return@filter false
            val minute = start.hour * 60 + start.minute
            minute in slot.startMinute until slot.startMinute + 30
        }
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = slot.label,
            style = RadarType.monoSmall,
            color = if (slot.isNow) CategoryColors.Vermilion else RadarColors.ink3,
            modifier = Modifier.width(48.dp).padding(top = 2.dp),
        )
        Box(Modifier.weight(1f).height(34.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(if (slot.isNow) CategoryColors.Vermilion else RadarColors.ruleSoft),
            )
            if (slot.isNow) {
                Text(
                    text = "NOW",
                    style = RadarType.labelMicro,
                    color = CategoryColors.Vermilion,
                    modifier = Modifier.padding(start = 4.dp, top = 3.dp),
                )
            }
            timed.firstOrNull()?.let { entry ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .background(RadarColors.paper3)
                            .border(1.dp, CategoryColors.Rust, RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.occurrence.item.title,
                        style = RadarType.microPlain,
                        color = RadarColors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.agendaSections(
    rows: List<CalendarDateEntity>,
    regionLabels: Map<String, String>,
) {
    agendaGroups(rows).forEach { group ->
        item(key = "agenda:${group.source}") {
            AgendaNamespaceHeader(
                label = regionLabels[group.source] ?: group.source,
                count = group.rows.size,
            )
        }
        items(group.rows, key = { "${it.source}:${it.feedUid}" }) { row ->
            ObservanceRow(
                row = row,
                regionLabel = regionLabels[row.source],
                showDate = false,
                showRegion = false,
            )
        }
    }
}

@Composable
private fun AgendaNamespaceHeader(
    label: String,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(RadarColors.paper3).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label.uppercase(), style = RadarType.labelMicro, color = RadarColors.ink2)
        Spacer(Modifier.weight(1f))
        Text(text = compactNumber(count), style = RadarType.labelMicro, color = RadarColors.ink3)
    }
    SoftRule()
}

private data class PlannerEntry(
    val occurrence: com.personalos.app.data.PlannerOccurrence,
    val tagIds: List<String>,
)

@Composable
private fun PlannerSection(
    entries: List<PlannerEntry>,
    tagLabels: Map<String, String>,
    workspaceNames: Map<String, String>,
    onOpen: (PlannerEntry) -> Unit,
) {
    if (entries.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().background(RadarColors.paper3).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "PERSONAL", style = RadarType.labelMicro, color = RadarColors.ink2)
        Spacer(Modifier.weight(1f))
        Text(text = compactNumber(entries.size), style = RadarType.labelMicro, color = RadarColors.ink3)
    }
    SoftRule()
    entries.forEach { entry ->
        val item = entry.occurrence.item
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(entry) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(30.dp).background(CategoryColors.Rust))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        buildString {
                            append(entry.occurrence.start?.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)) ?: "All day")
                            append(" · ")
                            append(workspaceNames[item.workspaceId] ?: "Personal")
                            val tags = entry.tagIds.mapNotNull { tagLabels[it] }
                            if (tags.isNotEmpty()) append(" · ").append(tags.joinToString(" / "))
                        },
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SoftRule()
    }
}

/** Public/regional observances get distinct marks; the feed kind is the fact. */
private fun kindColor(kind: String): Color =
    when (kind.lowercase(Locale.ENGLISH)) {
        "regional" -> CategoryColors.Indigo
        else -> CategoryColors.Rust
    }

/**
 * Shared with Travel's Calendar tab, so both surfaces draw observances the same
 * way. [regionLabel] is the provider's display name for the row's region; when
 * absent (a caller that has no source list), the slug is at least made readable.
 */
@Composable
internal fun ObservanceRow(
    row: CalendarDateEntity,
    regionLabel: String? = null,
    showDate: Boolean = true,
    showRegion: Boolean = true,
) {
    val region = regionLabel ?: CalendarDirectoryParser.displayName(row.region)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDate) {
                Text(
                    text = observanceDay(row.date),
                    style = RadarType.monoSmall,
                    color = RadarColors.ink3,
                    modifier = Modifier.width(56.dp),
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = calendarDisplayName(row.name, region),
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (showRegion) {
                            "${row.kind.uppercase(Locale.ENGLISH)} ${com.personalos.app.core.Chars.MIDDLE_DOT} $region ${com.personalos.app.core.Chars.MIDDLE_DOT} ALL DAY"
                        } else {
                            "${row.kind.uppercase(Locale.ENGLISH)} ${com.personalos.app.core.Chars.MIDDLE_DOT} ALL DAY"
                        },
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SoftRule()
    }
}

/** `1 CALENDAR` / `N CALENDARS` — the header never says "1 calendars". */
private fun calendarCountLabel(count: Int): String = if (count == 1) "1 CALENDAR" else "$count CALENDARS"
