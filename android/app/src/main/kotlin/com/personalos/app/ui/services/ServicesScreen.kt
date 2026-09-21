package com.personalos.app.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphActionButton
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.RadarAppBar
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SearchField
import com.personalos.app.ui.common.SectionHeader
import com.personalos.app.ui.common.TabSpec
import com.personalos.app.ui.common.TabStrip
import com.personalos.app.ui.common.TileGrid
import com.personalos.app.ui.common.TileSpec
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.theme.CategoryColors

@Composable
fun ServicesScreen(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val total = SERVICE_GROUPS.sumOf { it.tiles.size }
    val tabs =
        listOf(
            TabSpec("All", total),
            TabSpec("Radar", SERVICE_GROUPS[0].tiles.size),
            TabSpec("News", SERVICE_GROUPS[1].tiles.size),
            TabSpec("M&M", SERVICE_GROUPS[2].tiles.size),
            TabSpec("Media", SERVICE_GROUPS[3].tiles.size),
            TabSpec("App", SERVICE_GROUPS[4].tiles.size),
        )

    val shownGroups =
        if (selectedTab == 0) {
            SERVICE_GROUPS
        } else {
            listOf(SERVICE_GROUPS[selectedTab - 1])
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
            // Fixed header — does not scroll away.
            RadarAppBar(
                title = "Services",
                sub = "$total modules · ${SERVICE_GROUPS.size} groups",
                actions = {
                    GlyphActionButton(Glyph.Scan, onClick = {
                        onNavigate(Destination.Placeholder("Scan"))
                    })
                },
            )
            TabStrip(
                items = tabs,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
            )

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
                        SearchField("Search all services")
                    }
                    HardRule()

                    shownGroups.forEach { group ->
                        SectionHeader(
                            title = group.title,
                            accent = group.accent,
                            note = "${group.tiles.size} modules",
                        )
                        TileGrid(
                            items = group.tiles,
                            onTile = { tile ->
                                when (tile.name) {
                                    "Radar" -> onNavigate(Destination.Radar())
                                    "News" -> onNavigate(Destination.News)
                                    "RSS" -> onNavigate(Destination.Rss)
                                    "M&M" -> onNavigate(Destination.Money)
                                    "Weather" -> onNavigate(Destination.Weather)
                                    "Sources" -> onNavigate(Destination.Sources)
                                    "Calendar" -> onNavigate(Destination.Calendar)
                                    "Travel" -> onNavigate(Destination.Travel)
                                    "Settings" -> onNavigate(Destination.Settings)
                                    "Watchers", "Alerts" -> onNavigate(Destination.Watchers)
                                    else -> onNavigate(Destination.Placeholder(tile.name))
                                }
                            },
                        )
                    }

                    FooterStrip("Last refresh 09:44 · sample data")
                }
            }
        }
    }
}

data class ServiceGroup(
    val title: String,
    val accent: Color,
    val tiles: List<TileSpec>,
)

private val SERVICE_GROUPS: List<ServiceGroup> =
    listOf(
        ServiceGroup(
            title = "Radar & monitoring",
            accent = CategoryColors.Vermilion,
            tiles =
                listOf(
                    TileSpec("Radar", CategoryColors.Vermilion, Glyph.Radar),
                    TileSpec("Alerts", CategoryColors.Rust, Glyph.Bell),
                    TileSpec("Watchers", CategoryColors.Teal, Glyph.Eye),
                    TileSpec("Messages", CategoryColors.Vermilion, Glyph.Chat),
                ),
        ),
        ServiceGroup(
            title = "News & topics",
            accent = CategoryColors.Indigo,
            tiles =
                listOf(
                    TileSpec("News", CategoryColors.Indigo, Glyph.News),
                    TileSpec("Topics", CategoryColors.Chartreuse, Glyph.Tag, darkGlyph = true),
                    TileSpec("RSS", CategoryColors.Indigo, Glyph.Rss),
                    TileSpec("Social", CategoryColors.Periwinkle, Glyph.Chat),
                ),
        ),
        ServiceGroup(
            title = "Money & travel",
            accent = CategoryColors.Teal,
            tiles =
                listOf(
                    TileSpec("M&M", CategoryColors.Teal, Glyph.Banknote),
                    TileSpec("Travel", CategoryColors.Mustard, Glyph.Plane, darkGlyph = true),
                    TileSpec("Cards", CategoryColors.Plum, Glyph.Card),
                    TileSpec("Wallet", CategoryColors.Cyan, Glyph.Wallet),
                ),
        ),
        ServiceGroup(
            title = "Media & daily",
            accent = CategoryColors.Cyan,
            tiles =
                listOf(
                    TileSpec("Radio", CategoryColors.Cyan, Glyph.Broadcast),
                    TileSpec("Weather", CategoryColors.Chartreuse, Glyph.CloudRain),
                    TileSpec("Calendar", CategoryColors.Rust, Glyph.Clock),
                    TileSpec("Notes", RadarColors.ink, Glyph.Note),
                    TileSpec("Places", CategoryColors.Rust, Glyph.Place),
                ),
        ),
        ServiceGroup(
            title = "App",
            accent = RadarColors.ink,
            tiles =
                listOf(
                    TileSpec("Settings", RadarColors.ink2, Glyph.Tune),
                    TileSpec("Sources", RadarColors.ink, Glyph.Stack),
                    TileSpec("Account", CategoryColors.Plum, Glyph.Person),
                    TileSpec("Home", RadarColors.ink, Glyph.Home, outline = true),
                ),
        ),
    )
