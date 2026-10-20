package com.personalos.app.ui.tile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.personalos.app.core.tile.ActionKind
import com.personalos.app.core.tile.TileConfig
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.QuickBar
import com.personalos.app.ui.common.QuickItem
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SearchField
import com.personalos.app.ui.common.TabSpec
import com.personalos.app.ui.common.TabStrip

/**
 * The shared tile shell (docs/ARCHITECTURE.md §8): title, search, the tile's
 * context action bar, then the body.
 *
 * It deliberately reuses the *same* `SearchField` and `QuickBar` the Home screen
 * uses, so a tile's header is visually identical to Home's rather than a
 * lookalike. The scaffold owns the query and hands it to the body, so search
 * lives in one place instead of being reimplemented per tile.
 */
@Composable
fun TileScaffold(
    config: TileConfig,
    onAction: (ActionKind) -> Unit,
    modifier: Modifier = Modifier,
    /** The service's colour, used to fill the header band. */
    accent: Color = RadarColors.ink,
    /** Non-null only when this tile was pushed on top of another screen. */
    onBack: (() -> Unit)? = null,
    header: @Composable (() -> Unit)? = null,
    /** Empty for a single-view tile; non-empty adds the shared tab strip. */
    tabs: List<TabSpec> = emptyList(),
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    sourceFilters: List<SourceFilterOption> = emptyList(),
    selectedSource: String? = null,
    onSelectSource: (String?) -> Unit = {},
    body: @Composable (query: String) -> Unit,
) {
    // What is being typed, and what was last submitted. Search runs on the
    // submitted value only - pressing the arrow - never on every keystroke.
    var draft by remember { mutableStateOf("") }
    var committed by remember { mutableStateOf("") }

    val quickItems = remember(config) { config.actions.map { QuickItem(it.label, it.glyph()) } }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper2),
    ) {
        // The same header the pushed detail screens use, so a tile and its detail
        // page share one height and one tint colour.
        RadarHeader(
            title = config.title,
            accent = accent,
            onBack = onBack,
            trailing = header,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RadarColors.paper2)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            SearchField(
                hint = config.searchHint,
                query = draft,
                onQueryChange = { draft = it },
                onSubmit = { committed = draft },
            )
        }
        HardRule()

        QuickBar(
            items = quickItems,
            onAction = { item ->
                val index = quickItems.indexOf(item)
                if (index >= 0) onAction(config.actions[index])
            },
        )

        if (tabs.isNotEmpty()) {
            TabStrip(
                items = tabs,
                selectedIndex = selectedTab,
                onSelect = onSelectTab,
                trailing = {
                    SourceFilterDropdown(
                        options = sourceFilters,
                        selected = selectedSource,
                        onSelect = onSelectSource,
                    )
                },
            )
        }

        // Notifications are a root host (see `NotificationHost` in MainActivity),
        // not a child of this scaffold, so every screen shows them.

        body(committed)
    }
}
