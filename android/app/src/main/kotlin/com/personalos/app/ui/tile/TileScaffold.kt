package com.personalos.app.ui.tile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.tile.ActionKind
import com.personalos.app.core.tile.TileConfig
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.QuickBar
import com.personalos.app.ui.common.QuickItem
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SearchField
import com.personalos.app.ui.theme.RadarType

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

        SyncBar()

        body(committed)
    }
}

/**
 * Thin progress bar shown while a sync started from the UI is in flight, fed by
 * the shared [com.personalos.app.core.sync.SyncStatus]. Scheduled workers do not
 * post to it - they run with no screen attached and log under their own tags.
 * Every step it shows is also written to `adb logcat -s Sync`.
 */
@Composable
private fun SyncBar() {
    val container = LocalAppContainer.current
    val running by container.sync.running.collectAsStateWithLifecycle()
    if (!running) return

    val step by container.sync.step.collectAsStateWithLifecycle()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RadarColors.ink)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = step.ifBlank { "SYNCING" }.uppercase(),
            style = RadarType.micro,
            color = RadarColors.paper2,
            maxLines = 1,
        )
    }
}
