package com.personalos.app.ui.weather

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.personalos.app.core.tile.ActionKind
import com.personalos.app.core.tile.TileConfig
import com.personalos.app.ui.common.HeaderMeta
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.TabSpec
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.tile.TileScaffold
import kotlinx.coroutines.launch

private val CLIMATE_TILE =
    TileConfig(
        id = "climate",
        title = "Climate",
        searchHint = "Search climate",
        actions = listOf(ActionKind.SYNC),
    )

/** Global, slow climate signals. Separate from location-based Weather. */
@Composable
fun ClimateScreen(
    modifier: Modifier = Modifier,
    onNavigate: (Destination) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    TileScaffold(
        config = CLIMATE_TILE,
        modifier = modifier,
        accent = CategoryColors.Cyan,
        onBack = onBack,
        showSearch = false,
        header = {
            HeaderMeta(
                primary = if (selectedTab == 0) "GLOBAL" else "REGIONAL",
                secondary = if (selectedTab == 0) "SLOW SIGNALS" else "MONTHLY MAP",
            )
        },
        tabs =
            listOf(
                TabSpec("Global"),
                TabSpec("Regional"),
            ),
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        onAction = { action ->
            if (action == ActionKind.SYNC) {
                scope.launch { container.climateRepository.refresh() }
            }
        },
    ) {
        if (selectedTab == 1) {
            ClimateRegionalTab(Modifier.fillMaxSize())
        } else {
            ClimateTab(
                repository = container.climateRepository,
                onOpen = { onNavigate(Destination.ClimateDetail(it)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
