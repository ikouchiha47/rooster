package com.personalos.app.ui.weather

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.model.WeatherDay
import com.personalos.app.core.model.WeatherSnapshot
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
import kotlin.math.abs

/**
 * Weather: where you are, the places you follow, and the weather *news* that
 * mentions them.
 *
 * Two different sources feed one screen, which is the point of the tile model:
 *  - forecasts come from [com.personalos.app.core.provider.WeatherProvider];
 *  - the timeline below composes from the shared store through the `weather` tag,
 *    so a flood story that arrived over RSS shows up here without Weather knowing
 *    anything about RSS.
 *
 * The present location is a slot of its own. It is **not** one of the configured
 * places, so when no location is available the present row shows a placeholder
 * rather than borrowing a configured place - which is what used to make three
 * configured places render as two.
 *
 * Tapping a place opens its seven-day forecast.
 */
private val WEATHER_TILE =
    TileConfig(
        id = "weather",
        title = "Weather",
        searchHint = "Search places",
        actions = listOf(ActionKind.SYNC, ActionKind.ADD_LOCATION, ActionKind.ADD_RULE, ActionKind.SETTINGS),
    )

private const val WEATHER_NEWS_LIMIT = 25

/** One place cell in the Places strip: fixed width and one shared row height. */
private val PLACE_CELL_WIDTH = 96.dp
private val PLACE_ROW_HEIGHT = 74.dp

@Composable
fun WeatherScreen(
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

    val snapshots by
        remember(reload) { container.weather.observe() }
            .collectAsStateWithLifecycle(initialValue = emptyList())

    val maxId by dao.observeMaxId().collectAsStateWithLifecycle(initialValue = null)

    // ADR 0005 T19: the strip reads the store. Names come from enabled weather
    // sources; numbers come from the latest observation per identity. Empty
    // store renders a placeholder, never a borrowed provider value.
    var storePlaces by remember { mutableStateOf<List<StorePlace>>(emptyList()) }
    LaunchedEffect(maxId, reload) {
        val weatherSources =
            runCatching { database.sourceDao().enabled() }
                .getOrDefault(emptyList())
                .filter { it.kind == "weather" }
        val specs =
            weatherSources.mapNotNull { row ->
                runCatching {
                    val spec =
                        com.personalos.app.core.sources.SourceSpecs
                            .parse(row.kind, row.specJson)
                            as com.personalos.app.core.sources.WeatherSpec
                    val identity =
                        com.personalos.app.core.sources.SourceKeys
                            .sourceFor(row.id, spec)
                    spec.place to identity
                }.getOrNull()
            }
        val views =
            if (specs.isEmpty()) {
                emptyList()
            } else {
                container.observationRepository.latestMany(specs.map { it.second })
            }
        val bySource = views.associateBy { it.source }
        storePlaces =
            specs.map { (place, identity) ->
                val view = bySource[identity]
                val temp = (view?.fields?.get("temp_c") as? com.personalos.app.core.rules.FieldValue.Num)?.value
                StorePlace(
                    place = place,
                    temperatureC = temp?.let { kotlin.math.round(it).toInt() },
                    observed = view != null,
                    weatherCode = (view?.fields?.get("weather_code") as? com.personalos.app.core.rules.FieldValue.Num)?.value?.toInt(),
                )
            }
    }

    // The present-location seam. Null is the honest "we do not know where you
    // are" answer, and is rendered as such.
    val present by
        remember(container) { container.presentLocation.observe() }
            .collectAsStateWithLifecycle(initialValue = null)

    var news by remember { mutableStateOf<List<TaggedEvent>>(emptyList()) }
    var weatherMentions by remember { mutableStateOf<Map<Long, List<MentionEntity>>>(emptyMap()) }

    LaunchedEffect(maxId, reload) {
        val page = dao.pageByTag(Tags.WEATHER, null, 0L, WEATHER_NEWS_LIMIT)
        news = page
        // Same one-batched-read-per-page shape as News: the timeline renders
        // first, mentions fill the meta lines without moving row heights.
        weatherMentions =
            if (page.isEmpty()) {
                emptyMap()
            } else {
                val rows = database.mentionDao().forItems(page.map { it.event.ulid })
                val idByUlid = page.associate { it.event.ulid to it.event.id }
                rows.groupBy { idByUlid[it.itemId] ?: -1L }.filterKeys { it != -1L }
            }
    }

    val listState = rememberLazyListState()

    // Same new-items badge as News, for the weather-tagged timeline below:
    // how many rows landed above the reader while scrolled down. UI-side; the
    // store underneath is untouched.
    var pendingNews by remember { mutableStateOf(0) }
    var newsHeadSeenId by remember { mutableStateOf<Long?>(null) }
    var collapsedDays by remember { mutableStateOf(setOf<Long>()) }
    val awayFromTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    // A head change with the reader down means fresh weather items slid in
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
        config = WEATHER_TILE,
        modifier = modifier,
        accent = CategoryColors.Cyan,
        onBack = onBack,
        header = {
            Text(
                text = "${storePlaces.size} PLACES",
                style = RadarType.micro,
                color = RadarColors.paper4,
            )
        },
        onAction = { action ->
            when (action) {
                ActionKind.SYNC ->
                    scope.launch {
                        container.sync.run("weather") {
                            container.sync.step("refreshing observations")
                            runCatching { container.feeds.refresh(force = true) }
                            reload++
                            container.sync.step("done: ${storePlaces.size} places")
                        }
                    }

                else -> Unit
            }
        },
    ) { query ->
        val places =
            remember(storePlaces, query) {
                val q = query.trim()
                if (q.isEmpty()) {
                    storePlaces
                } else {
                    storePlaces.filter { it.place.contains(q, ignoreCase = true) }
                }
            }

        // The root week is the PRESENT location's forecast when a fix exists; a
        // configured place's own week lives on that place's forecast screen, not
        // here. Only when there is no fix does it fall back to the first place the
        // user can currently see, so search still filters the week.
        val weekPlace =
            present?.place
                ?: places.firstOrNull()?.place
                ?: snapshots.firstOrNull()?.place
        val weekNote = present?.let(::positionLabel) ?: weekPlace.orEmpty()
        // The week reads the store: `weather:<slug>:fc:<date>` rows mapped to
        // day cells. Empty store hides the card (existing behaviour below) —
        // never a borrowed provider cache.
        var week by remember { mutableStateOf<List<WeatherDay>>(emptyList()) }
        LaunchedEffect(weekPlace, maxId, reload) {
            week =
                if (weekPlace == null) {
                    emptyList()
                } else {
                    val slug =
                        com.personalos.app.core.sources.SourceKeys
                            .slug(weekPlace)
                    container.observationRepository.forecastWeek(slug)
                }
        }

        // Day buckets for the weather timeline: display-only grouping over the
        // page, so the one batched mention read per page is untouched and row
        // keys stay `event:id`.
        val newsGroups = remember(news) { groupIntoDays(news) { it.event.timestamp } }
        // Position of the weather-news header: present, then the week when it
        // exists, then places. Collapsing never moves it - collapsed rows sit
        // below it - so the badge can scroll straight to it.
        val newsHeaderIndex = 1 + (if (week.isNotEmpty()) 1 else 0) + 1

        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "present") {
                    CardSection {
                        PresentCard(present) {
                            present?.let { onNavigate(Destination.Forecast(it.place)) }
                        }
                    }
                }

                if (week.isNotEmpty()) {
                    item(key = "week") {
                        CardSection { WeekCard(note = weekNote, days = week) }
                    }
                }

                item(key = "places") {
                    CardSection {
                        StorePlacesCard(
                            places = places,
                            emptyNote = if (storePlaces.isEmpty()) "No places configured." else "No places match.",
                            onOpen = { onNavigate(Destination.Forecast(it.place)) },
                        )
                    }
                }

                item(key = "news-header") {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        WidgetHeader(
                            title = "Weather news",
                            note = news.size.toString(),
                            glyph = Glyph.News,
                        )
                    }
                }
                if (news.isEmpty()) {
                    item(key = "news-empty") {
                        Text(
                            text = "Nothing tagged weather yet.",
                            style = RadarType.body,
                            color = RadarColors.ink3,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
                newsGroups.forEach { group ->
                    stickyHeader(key = "wday:${group.dayStart}") {
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
                            WeatherNewsLine(item, mentions = weatherMentions[item.event.id].orEmpty())
                        }
                    }
                }

                item(key = "footer") {
                    FooterStrip(
                        text =
                            "${storePlaces.size} places ${Chars.MIDDLE_DOT} " +
                                "${news.size} weather items ${Chars.MIDDLE_DOT} end of list",
                    )
                }
            }

            // The body is a long feed; without this the first screenful read as a
            // static page. Thin and ink-tinted so it belongs to the print look.
            ThinScrollbar(
                state = listState,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp),
            )

            if (pendingNews > 0 && awayFromTop) {
                NewItemsPill(
                    count = pendingNews,
                    onTap = {
                        pendingNews = 0
                        scope.launch { listState.animateScrollToItem(newsHeaderIndex) }
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
            }
        }
    }
}

/** Fraction of the track the thumb shrinks to at most, so short lists still mark. */
private const val MIN_THUMB_FRACTION = 0.08f

/** Shortest the thumb ever gets, so it stays findable on a long list. */
private val MIN_THUMB_HEIGHT = 20.dp

/**
 * A thin, always-visible scroll indicator.
 *
 * Compose ships no scrollbar on Android, and this body is a long feed: without a
 * mark, the first screenful read as a static page. Geometry comes from the list's
 * own layout, so nothing extra needs tracking.
 */
@Composable
private fun ThinScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val layout = state.layoutInfo
    val visible = layout.visibleItemsInfo
    val total = layout.totalItemsCount
    if (total <= 0 || visible.isEmpty()) return

    val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
    val first = visible.first()
    val last = visible.last()
    val averageItem = ((last.offset + last.size) - first.offset).toFloat() / visible.size
    val totalContent = averageItem * total
    if (viewport <= 0f || totalContent <= viewport) return

    val thumbFraction = (viewport / totalContent).coerceIn(MIN_THUMB_FRACTION, 1f)
    val maxScroll = (totalContent - viewport).coerceAtLeast(1f)
    val scrolled = (first.index * averageItem - first.offset).coerceIn(0f, maxScroll)
    val progress = scrolled / maxScroll

    Canvas(modifier.width(3.dp).fillMaxHeight()) {
        val thumbHeight = (size.height * thumbFraction).coerceAtLeast(MIN_THUMB_HEIGHT.toPx())
        val top = (size.height - thumbHeight) * progress
        drawRoundRect(
            color = RadarColors.ink.copy(alpha = 0.45f),
            topLeft = Offset(0f, top),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(size.width / 2f),
        )
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
 * The present location, as a wide confined card.
 *
 * The card has no place *name* to show: reverse geocoding is deliberately not
 * done offline, and promoting a configured place into this slot is exactly the
 * bug that made three places look like two. So it identifies itself by its
 * coordinates, which is the one thing it actually knows. When there is no fix it
 * says so plainly - a dash, "not available", and no invented place name.
 */
@Composable
private fun PresentCard(
    present: WeatherSnapshot?,
    onOpen: () -> Unit,
) {
    val available = present != null
    Column(
        Modifier
            .fillMaxWidth()
            .background(RadarColors.paper2)
            .border(1.dp, RadarColors.ink)
            .then(if (available) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        WidgetHeader(
            title = "Present location",
            note = present?.let(::positionLabel) ?: "not available",
            glyph = Glyph.Place,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(
                glyph = present?.condition()?.glyph ?: Glyph.Cloud,
                tint = if (available) RadarColors.ink else RadarColors.ink3,
                size = 38.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = present?.let { "${it.temperatureC}${Chars.DEGREE}" } ?: Chars.EM_DASH,
                        style = RadarType.monoH2,
                        color = if (available) RadarColors.ink else RadarColors.ink3,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (available) present?.summary?.uppercase().orEmpty() else "NOT AVAILABLE",
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
                        present?.detail?.takeIf { it.isNotBlank() }
                            ?: "location services unavailable",
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
 * The cached week as a dense horizontal strip - a column per day, not a row per
 * day - so all seven days read at a glance in about half the height.
 */
@Composable
private fun WeekCard(
    note: String,
    days: List<WeatherDay>,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(RadarColors.paper2)
            .border(1.dp, RadarColors.ink),
    ) {
        WidgetHeader(title = "7-day forecast", note = note, glyph = Glyph.Sun)
        ForecastStrip(days)
    }
}

/**
 * Every configured place, in one ruled card.
 *
 * A single horizontally-scrolling row of fixed-width cells - the same shape as
 * the forecast strip - rather than an equal-width grid that wraps. Cells keep
 * one width and the row keeps one height whatever the count, so three places and
 * twelve places both read the same; the row scrolls instead of reflowing. Each
 * place is always present, never consumed by the present-location slot.
 */
@Composable
private fun PlacesCard(
    places: List<WeatherSnapshot>,
    emptyNote: String,
    onOpen: (WeatherSnapshot) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(RadarColors.paper2)
            .border(1.dp, RadarColors.ink),
    ) {
        WidgetHeader(
            title = "Places",
            note = places.size.takeIf { it > 0 }?.toString(),
            glyph = Glyph.Place,
        )
        if (places.isEmpty()) {
            Text(
                text = emptyNote,
                style = RadarType.small,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )
        } else {
            // Cells share the card width equally down to a 96dp floor, mirroring
            // ForecastStrip: the row fills edge to edge and only scrolls on
            // screens narrower than all minimums.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fits = maxWidth >= PLACE_CELL_WIDTH * places.size
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(PLACE_ROW_HEIGHT)
                            .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                ) {
                    places.forEachIndexed { index, snapshot ->
                        if (index > 0) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
                        }
                        PlaceCell(
                            snapshot = snapshot,
                            modifier =
                                if (fits) {
                                    Modifier.weight(1f).fillMaxHeight()
                                } else {
                                    Modifier.width(PLACE_CELL_WIDTH).fillMaxHeight()
                                },
                            onOpen = { onOpen(snapshot) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * ADR 0005 T19: one strip cell as the store sees it. `temperatureC` null means
 * no observation yet — the cell says so plainly rather than borrowing a
 * provider cache value.
 */
private data class StorePlace(
    val place: String,
    val temperatureC: Int?,
    val observed: Boolean,
    val weatherCode: Int? = null,
)

@Composable
private fun StorePlacesCard(
    places: List<StorePlace>,
    emptyNote: String,
    onOpen: (StorePlace) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(RadarColors.paper2)
            .border(1.dp, RadarColors.ink),
    ) {
        WidgetHeader(
            title = "Places",
            note = places.size.takeIf { it > 0 }?.toString(),
            glyph = Glyph.Place,
        )
        if (places.isEmpty()) {
            Text(
                text = emptyNote,
                style = RadarType.small,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fits = maxWidth >= PLACE_CELL_WIDTH * places.size
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(PLACE_ROW_HEIGHT)
                            .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                ) {
                    places.forEachIndexed { index, place ->
                        if (index > 0) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
                        }
                        StorePlaceCell(
                            place = place,
                            modifier =
                                if (fits) {
                                    Modifier.weight(1f).fillMaxHeight()
                                } else {
                                    Modifier.width(PLACE_CELL_WIDTH).fillMaxHeight()
                                },
                            onOpen = { onOpen(place) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorePlaceCell(
    place: StorePlace,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val condition = place.weatherCode?.let(::weatherCodeCondition)
    Column(
        modifier =
            modifier
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            text = place.place,
            style = RadarType.micro,
            color = RadarColors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(
                glyph = condition?.glyph ?: Glyph.Place,
                tint = if (place.observed) RadarColors.ink else RadarColors.ink3,
                size = 22.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = place.temperatureC?.let { "$it${Chars.DEGREE}" } ?: Chars.EM_DASH,
                style = RadarType.monoStat,
                color = if (place.observed) RadarColors.ink else RadarColors.ink3,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = condition?.label ?: if (place.observed) "Observed" else "No observation yet",
            style = RadarType.microPlain,
            color = RadarColors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaceCell(
    snapshot: WeatherSnapshot,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val unavailable = snapshot.summary.equals("Unavailable", ignoreCase = true)
    Column(
        modifier =
            modifier
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            text = snapshot.place,
            style = RadarType.micro,
            color = RadarColors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(
                glyph = snapshot.condition().glyph,
                tint = if (unavailable) RadarColors.ink3 else RadarColors.ink,
                size = 22.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${snapshot.temperatureC}${Chars.DEGREE}",
                style = RadarType.monoStat,
                color = if (unavailable) RadarColors.ink3 else RadarColors.ink,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = snapshot.summary,
            style = RadarType.microPlain,
            color = RadarColors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One weather-tagged item, composed from the shared store rather than fetched. */
@Composable
private fun WeatherNewsLine(
    item: TaggedEvent,
    mentions: List<MentionEntity> = emptyList(),
) {
    // Same row language as News: 3dp subject spine at the edge, fixed two-line
    // headline block, one-line meta. The spine reads the FULL tag list so the
    // `weather` marker implied by this tile still colours the edge Chartreuse;
    // the meta line gets the filtered list so it does not print WEATHER forty
    // times. Rows with no other subject than weather still scan as weather.
    Column(Modifier.fillMaxWidth()) {
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
                // Fixed two-line block, same defect and fix as News: the old Text
                // had maxLines=2 with no width modifier, so it measured
                // wrap-content - its paragraph took its preferred single-line
                // width and Ellipsis trimmed that one line, and maxLines never
                // engaged because nothing handed the paragraph the row's width
                // as a wrap boundary. fillMaxWidth wraps it first and only then
                // ellipsises; minLines pins the Text to the full block so every
                // row measures one height whatever the headline length.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(weatherTitleBlockHeight()),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = item.event.title,
                        style = RadarType.serifTitle,
                        color = RadarColors.ink,
                        minLines = WEATHER_TITLE_LINES,
                        maxLines = WEATHER_TITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(3.dp))
                // `weather` is implied by this tile, so it is dropped from the chips.
                TagLine(
                    tags = item.tagList.filter { it != Tags.WEATHER },
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
 * same fixed block News uses: a one-line headline centres inside it instead
 * of pulling its meta line up, so the list scans as a grid.
 */
private const val WEATHER_TITLE_LINES = 2

@Composable
private fun weatherTitleBlockHeight(): Dp =
    with(LocalDensity.current) {
        (RadarType.serifTitle.lineHeight * WEATHER_TITLE_LINES).toDp()
    }

/**
 * A snapshot's position as `22.57°N 88.36°E`.
 *
 * The present location has no name offline, so coordinates are the honest label.
 * A snapshot without coordinates says so rather than showing a blank.
 */
private fun positionLabel(snapshot: WeatherSnapshot): String {
    val lat = snapshot.lat ?: return "position unknown"
    val lon = snapshot.lon ?: return "position unknown"
    val ns = if (lat < 0) "S" else "N"
    val ew = if (lon < 0) "W" else "E"
    return "${"%.2f".format(abs(lat))}${Chars.DEGREE}$ns ${"%.2f".format(abs(lon))}${Chars.DEGREE}$ew"
}
