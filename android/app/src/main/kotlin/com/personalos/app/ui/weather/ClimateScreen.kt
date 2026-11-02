package com.personalos.app.ui.weather

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.personalos.app.core.tile.ActionKind
import com.personalos.app.core.tile.TileConfig
import com.personalos.app.ui.common.HeaderMeta
import com.personalos.app.ui.common.LocalAppContainer
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
    onBack: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    TileScaffold(
        config = CLIMATE_TILE,
        modifier = modifier,
        accent = CategoryColors.Cyan,
        onBack = onBack,
        showSearch = false,
        header = { HeaderMeta(primary = "GLOBAL", secondary = "SLOW SIGNALS") },
        onAction = { action ->
            if (action == ActionKind.SYNC) {
                scope.launch { container.climateRepository.refresh() }
            }
        },
    ) {
        ClimateTab(
            repository = container.climateRepository,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
