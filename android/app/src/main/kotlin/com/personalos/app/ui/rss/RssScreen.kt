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
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.sources.FeedList
import com.personalos.app.ui.sources.UserFeedForm
import com.personalos.app.ui.theme.CategoryColors

/**
 * RSS: the feeds this device polls, and the form that adds more.
 *
 * The form lives in [com.personalos.app.ui.sources.UserFeedForm] and the feed
 * list lives in [com.personalos.app.ui.sources.FeedList] — both are views over
 * the same sources store, reused by the Overview screen so there is one
 * implementation and two entry points.
 */
@Composable
fun RssScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val sources by container.sourceRepository.observe().collectAsState(initial = emptyList())
    val statuses by container.feeds.statuses.collectAsState()
    val sourceStatuses by container.feeds.sourceStatuses.collectAsState()

    Column(modifier.fillMaxSize()) {
        RadarHeader(
            title = "RSS",
            accent = CategoryColors.Indigo,
            onBack = onBack,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            UserFeedForm(sources = sources)
            WidgetHeader(title = "Feeds")
            FeedList(statuses = statuses + sourceStatuses)
        }
    }
}
