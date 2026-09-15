package com.personalos.app.ui.rss

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.sources.UserFeedsSection
import com.personalos.app.ui.theme.CategoryColors

/**
 * RSS: the feeds this device polls, and the form that adds more.
 *
 * The section itself lives in `ui/sources/UserFeedsSection` because the Sources
 * screen shows the same content — this screen is the tile's own entrance to it,
 * so there is one implementation and two views over the same rules store.
 */
@Composable
fun RssScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val rules by container.ruleRepository.observe().collectAsState(initial = emptyList())

    Column(modifier.fillMaxSize()) {
        RadarHeader(
            title = "RSS",
            accent = CategoryColors.Indigo,
            onBack = onBack,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            UserFeedsSection(rules = rules)
        }
    }
}
