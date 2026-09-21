package com.personalos.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.model.FxRate
import com.personalos.app.core.model.Money
import com.personalos.app.core.model.WeatherSnapshot
import com.personalos.app.ui.common.CarouselDots
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphActionButton
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.MiniCard
import com.personalos.app.ui.common.QuickBar
import com.personalos.app.ui.common.QuickItem
import com.personalos.app.ui.common.RadarAppBar
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SearchField
import com.personalos.app.ui.common.SectionHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.StatCell
import com.personalos.app.ui.common.StatValue
import com.personalos.app.ui.common.SwipeStat
import com.personalos.app.ui.common.SwipeStatPage
import com.personalos.app.ui.common.TileGrid
import com.personalos.app.ui.common.TileSpec
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.common.color
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            // Every widget below depends on a provider *interface* and collects
            // its own state, so one data point updating recomposes only itself.
            HomeAppBar(onScan = { onNavigate(Destination.Placeholder("Scan")) })

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(RadarColors.paper2)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        SearchField("Search sources, rules, places")
                    }
                    HardRule()

                    QuickBar(
                        items =
                            listOf(
                                QuickItem("Scan", Glyph.Scan),
                                QuickItem("New rule", Glyph.Plus),
                                QuickItem("Add feed", Glyph.Rss),
                                QuickItem("Snooze", Glyph.Clock),
                                QuickItem("Sync", Glyph.Refresh),
                            ),
                        onAction = { onNavigate(Destination.Placeholder(it.label)) },
                    )

                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                    ) {
                        AtAGlanceCard(onNavigate = onNavigate)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().height(CARD_ROW_HEIGHT),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            NowPlayingCard(Modifier.weight(1f).fillMaxHeight())
                            WeatherCard(Modifier.weight(1f).fillMaxHeight())
                        }
                    }

                    SectionHeader(
                        title = "Services",
                        accent = CategoryColors.Vermilion,
                        note = "${HOME_TILES.size} of 24",
                    )
                    TileGrid(
                        items = HOME_TILES,
                        onTile = { tile ->
                            when (tile.name) {
                                "Radar" -> onNavigate(Destination.Radar())
                                "News" -> onNavigate(Destination.News)
                                "RSS" -> onNavigate(Destination.Rss)
                                "M&M" -> onNavigate(Destination.Money)
                                "Weather" -> onNavigate(Destination.Weather)
                                "Calendar" -> onNavigate(Destination.Calendar)
                                "Travel" -> onNavigate(Destination.Travel)
                                "Settings" -> onNavigate(Destination.Settings)
                                "Watchers", "Alerts" -> onNavigate(Destination.Watchers)
                                else -> onNavigate(Destination.Placeholder(tile.name))
                            }
                        },
                    )

                    SectionHeader(
                        title = "Pinned & recent",
                        accent = CategoryColors.Mustard,
                        note = "3 items",
                        onInk = true,
                    )
                    PinnedSection()

                    FooterStrip("Last refresh 09:44 ${Chars.MIDDLE_DOT} sample data")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ widgets

@Composable
private fun HomeAppBar(onScan: () -> Unit) {
    val container = LocalAppContainer.current
    val flow = remember(container) { container.glance.observe() }
    val glance by flow.collectAsStateWithLifecycle(initialValue = null)

    RadarAppBar(
        title = "Personal Radar",
        sub = "Hub ${Chars.MIDDLE_DOT} ${compactNumber(glance?.totalEvents ?: 0)} events",
        mark = true,
        actions = { GlyphActionButton(Glyph.Scan, onClick = onScan) },
    )
}

@Composable
private fun AtAGlanceCard(onNavigate: (Destination) -> Unit) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val database =
        remember {
            com.personalos.app.data.AppDatabase
                .getInstance(context)
        }
    val maxId by database.eventDao().observeMaxId().collectAsStateWithLifecycle(initialValue = null)
    val glanceFlow = remember(container) { container.glance.observe() }
    val glance by glanceFlow.collectAsStateWithLifecycle(initialValue = null)
    // FX watch reads the store like the M&M tile; empty store swipes nowhere.
    var rates by remember { androidx.compose.runtime.mutableStateOf<List<FxRate>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(maxId) {
        val specs =
            runCatching { database.sourceDao().enabled() }
                .getOrDefault(emptyList())
                .filter { it.kind == "fx" }
                .mapNotNull { row ->
                    runCatching {
                        val spec =
                            com.personalos.app.core.sources.SourceSpecs
                                .parse(row.kind, row.specJson)
                                as com.personalos.app.core.sources.FxSpec
                        spec.pair to
                            com.personalos.app.core.sources.SourceKeys
                                .sourceFor(row.id, spec)
                    }.getOrNull()
                }
        rates = container.observationRepository.fxRates(specs)
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RadarColors.paper2)
                .border(1.dp, RadarColors.ink),
    ) {
        WidgetHeader(title = "At a glance", note = "rolling 24h")

        Row(modifier = Modifier.height(AT_GLANCE_ROW_HEIGHT)) {
            StatCell(
                label = "Money out today",
                value = moneyText(glance?.moneyOutToday),
                suffix = glance?.moneyOutToday?.note,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
            // Small cell, N tracked rates: swipeable rather than truncated.
            SwipeStat(
                label = "FX watch",
                pages = rates.map { fxPage(it) },
                modifier = Modifier.weight(1f).padding(8.dp),
            )
        }

        SoftRule()

        Row(modifier = Modifier.height(AT_GLANCE_ROW_HEIGHT)) {
            StatCell(
                label = "Alerts fired",
                value = pad(glance?.alertsFired24h ?: 0),
                suffix = "last 24h",
                valueColor = CategoryColors.Vermilion,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(RadarColors.ruleSoft))
            StatCell(
                label = "Events 24h",
                value = compactNumber(glance?.events24h ?: 0),
                suffix = "all sources",
                valueColor = CategoryColors.Teal,
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable { onNavigate(Destination.Radar(com.personalos.app.ui.radar.radarEventsTabIndex)) },
            )
        }
    }
}

@Composable
private fun NowPlayingCard(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val flow = remember(container) { container.nowPlaying.observe() }
    val nowPlaying by flow.collectAsStateWithLifecycle(initialValue = null)

    Column(
        modifier =
            modifier
                .background(RadarColors.paper2)
                .border(1.dp, RadarColors.ink),
    ) {
        WidgetHeader(title = "Now playing")
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RadarColors.ink)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Equalizer()
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = nowPlaying?.station ?: "Nothing playing",
                    style = RadarType.serifTitle,
                    color = RadarColors.paper2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        nowPlaying?.let {
                            "${it.place.uppercase()} ${Chars.MIDDLE_DOT} ${it.bitrateKbps}K"
                        } ?: "pick a station",
                    style = RadarType.micro,
                    color = RadarColors.paper4,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(CategoryColors.Vermilion)
                    .clickable { }
                    .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "STOP", style = RadarType.labelMicro, color = Color.White)
        }
    }
}

@Composable
private fun WeatherCard(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val database =
        remember {
            com.personalos.app.data.AppDatabase
                .getInstance(context)
        }
    val maxId by database.eventDao().observeMaxId().collectAsStateWithLifecycle(initialValue = null)
    // Same store as the Weather tile: one page per enabled weather source, so
    // Home and the tile can never disagree on how many places exist.
    var snapshots by remember { androidx.compose.runtime.mutableStateOf<List<WeatherSnapshot>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(maxId) {
        val specs =
            runCatching { database.sourceDao().enabled() }
                .getOrDefault(emptyList())
                .filter { it.kind == "weather" }
                .mapNotNull { row ->
                    runCatching {
                        val spec =
                            com.personalos.app.core.sources.SourceSpecs
                                .parse(row.kind, row.specJson)
                                as com.personalos.app.core.sources.WeatherSpec
                        spec to
                            com.personalos.app.core.sources.SourceKeys
                                .sourceFor(row.id, spec)
                    }.getOrNull()
                }
        val views =
            if (specs.isEmpty()) {
                emptyList()
            } else {
                container.observationRepository.latestMany(specs.map { it.second })
            }
        val bySource = views.associateBy { it.source }
        snapshots =
            specs.mapNotNull { (spec, identity) ->
                val view = bySource[identity] ?: return@mapNotNull null
                val fields = view.fields
                val temp = (fields["temp_c"] as? com.personalos.app.core.rules.FieldValue.Num)?.value ?: return@mapNotNull null
                val code = (fields["weather_code"] as? com.personalos.app.core.rules.FieldValue.Num)?.value?.toInt()
                val humidity = (fields["humidity_pct"] as? com.personalos.app.core.rules.FieldValue.Num)?.value?.toInt()
                val wind = (fields["wind_kmh"] as? com.personalos.app.core.rules.FieldValue.Num)?.value?.toInt()
                WeatherSnapshot(
                    place = spec.place,
                    temperatureC = kotlin.math.round(temp).toInt(),
                    summary =
                        code?.let {
                            com.personalos.app.ui.weather
                                .weatherCodeCondition(it)
                                .label
                        } ?: "Observed",
                    detail =
                        listOfNotNull(
                            humidity?.let { "$it%" },
                            wind?.let { "wind $it km/h" },
                        ).joinToString(" ${Chars.MIDDLE_DOT} "),
                    lat = spec.lat,
                    lon = spec.lon,
                )
            }
    }

    Column(
        modifier =
            modifier
                .background(RadarColors.paper2)
                .border(1.dp, RadarColors.ink),
    ) {
        WidgetHeader(title = "Weather", glyph = Glyph.Cloud)

        when {
            // Same shape as a real page, so nothing shifts when data lands.
            snapshots.isEmpty() ->
                WeatherPage(
                    WeatherSnapshot(
                        place = "loading",
                        temperatureC = 0,
                        summary = "loading",
                        detail = "",
                    ),
                    placeholder = true,
                )

            snapshots.size == 1 -> WeatherPage(snapshots[0])

            else -> {
                // One page per configured location; dots are tappable.
                val state = rememberPagerState(pageCount = { snapshots.size })
                val scope = rememberCoroutineScope()
                HorizontalPager(
                    state = state,
                    modifier = Modifier.fillMaxWidth(),
                ) { index ->
                    WeatherPage(snapshots[index])
                }
                CarouselDots(
                    count = snapshots.size,
                    current = state.currentPage,
                    modifier = Modifier.padding(vertical = 4.dp),
                    onDotClick = { index -> scope.launch { state.animateScrollToPage(index) } },
                )
            }
        }
    }
}

@Composable
private fun WeatherPage(
    snapshot: WeatherSnapshot,
    placeholder: Boolean = false,
) {
    Column(modifier = Modifier.padding(8.dp)) {
        StatValue(
            value = if (placeholder) "--" else "${snapshot.temperatureC}${Chars.DEGREE}",
            suffix = snapshot.summary.uppercase(),
            valueStyle = RadarType.monoH2,
            suffixStyle = RadarType.labelMicro,
            suffixColor = if (placeholder) RadarColors.ink3 else CategoryColors.Cyan,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text =
                if (placeholder) {
                    "${Chars.MIDDLE_DOT} loading forecast"
                } else {
                    "${snapshot.place} ${Chars.MIDDLE_DOT} ${snapshot.detail}"
                },
            style = RadarType.microPlain,
            color = RadarColors.ink3,
            maxLines = 2,
        )
    }
}

@Composable
private fun PinnedSection() {
    val container = LocalAppContainer.current
    val flow = remember(container) { container.pinned.observe() }
    val items by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    if (items.isEmpty()) return

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
    ) {
        items.firstOrNull()?.let { item ->
            MiniCard(
                label = item.label,
                title = item.title,
                meta = item.meta,
                accent = item.accent.color(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        items.drop(1).chunked(2).forEach { pair ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { item ->
                    MiniCard(
                        label = item.label,
                        title = item.title,
                        meta = item.meta,
                        accent = item.accent.color(),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Equalizer() {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        listOf(4, 9, 6, 12, 5).forEach { barHeight ->
            Box(
                Modifier
                    .width(2.dp)
                    .height(barHeight.dp)
                    .background(CategoryColors.Vermilion),
            )
        }
    }
}

// ---------------------------------------------------------------- utilities

private fun moneyText(money: Money?): String = money?.let { "${Chars.RUPEE}${"%,.0f".format(it.amount)}" } ?: "n/a"

private fun fxPage(rate: FxRate): SwipeStatPage =
    SwipeStatPage(
        value = "%.2f".format(rate.rate),
        suffix = rate.pair,
        color = RadarColors.ink,
    )

private fun pad(value: Int): String = value.toString().padStart(2, '0')

// Rows that contain a pager (a SubcomposeLayout) must not be measured with
// IntrinsicSize - intrinsic measurement of lazy layouts throws at runtime.
private val AT_GLANCE_ROW_HEIGHT = 84.dp
private val CARD_ROW_HEIGHT = 140.dp

private val HOME_TILES: List<TileSpec> =
    listOf(
        TileSpec("Radar", CategoryColors.Vermilion, Glyph.Radar),
        TileSpec("Alerts", CategoryColors.Rust, Glyph.Bell),
        TileSpec("M&M", CategoryColors.Teal, Glyph.Banknote),
        TileSpec("News", CategoryColors.Indigo, Glyph.News),
        TileSpec("Travel", CategoryColors.Mustard, Glyph.Plane, darkGlyph = true),
        TileSpec("Social", CategoryColors.Periwinkle, Glyph.Chat),
        TileSpec("Jobs", CategoryColors.Plum, Glyph.Briefcase),
        TileSpec("Radio", CategoryColors.Cyan, Glyph.Broadcast),
        TileSpec("Topics", CategoryColors.Chartreuse, Glyph.Tag, darkGlyph = true),
        TileSpec("RSS", CategoryColors.Indigo, Glyph.Rss),
        TileSpec("Watchers", CategoryColors.Teal, Glyph.Eye),
        TileSpec("Weather", CategoryColors.Cyan, Glyph.CloudRain),
        TileSpec("Calendar", CategoryColors.Rust, Glyph.Clock),
        TileSpec("Notes", RadarColors.ink, Glyph.Note),
        TileSpec("Settings", RadarColors.ink2, Glyph.Tune),
        TileSpec("More", RadarColors.ink, Glyph.Plus, outline = true),
    )
