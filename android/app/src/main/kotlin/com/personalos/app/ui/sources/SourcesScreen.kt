package com.personalos.app.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personalos.app.core.feed.FeedStatus
import com.personalos.app.data.AppDatabase
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch

/**
 * Sources: the health of every feed, plus what the store actually holds.
 *
 * This screen only reads and manually triggers the ingest - all fetching lives
 * in FeedIngestor, reached here the same way the background worker reaches it.
 */
@Composable
fun SourcesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val statuses by container.feeds.statuses.collectAsState()
    val database = remember { AppDatabase.getInstance(context) }
    val totalItems by database.eventDao().observeCount().collectAsState(initial = 0)
    val tagCounts by database.itemTagDao().observeTagCounts().collectAsState(initial = emptyList())
    val sourceCounts by database.eventDao().observeSourceCounts().collectAsState(initial = emptyList())

    var syncing by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Sources", style = RadarType.serifH1, color = RadarColors.ink)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (syncing) "SYNCING" else "SYNC",
                style = RadarType.labelMicro,
                color = if (syncing) RadarColors.ink3 else RadarColors.ink,
                modifier =
                    Modifier
                        .clickable(enabled = !syncing) {
                            syncing = true
                            scope.launch {
                                runCatching { container.feeds.refresh() }
                                // Then catch up anything the active tagger has not seen.
                                runCatching { container.retagger.run() }
                                syncing = false
                            }
                        }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        HardRule()

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "${statuses.size} FEEDS · $totalItems ITEMS · ${tagCounts.size} TAGS",
                    style = RadarType.micro,
                    color = RadarColors.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            item {
                WidgetHeader(
                    title = "Feeds",
                    note = "${statuses.count { it.ok }}/${statuses.size} ok",
                )
            }
            items(statuses, key = { it.id }) { status -> FeedRow(status) }

            item { SectionGap() }
            item { WidgetHeader(title = "By tag", note = "${tagCounts.size}") }
            items(tagCounts, key = { "tag-${it.tag}" }) { row ->
                CountRow(left = row.tag, right = row.count)
            }

            item { SectionGap() }
            item { WidgetHeader(title = "By source", note = "${sourceCounts.size}") }
            items(sourceCounts, key = { "src-${it.source}" }) { row ->
                CountRow(left = row.source, right = row.count)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionGap() {
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun FeedRow(status: FeedStatus) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = status.name,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 1,
                )
                Text(
                    text = status.lastError?.let { "${status.host} · $it" } ?: status.host,
                    style = RadarType.microPlain,
                    color = if (status.lastError != null) CategoryColors.Vermilion else RadarColors.ink3,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = ago(status.lastOkAt),
                    style = RadarType.monoSmall,
                    color = RadarColors.ink2,
                )
                Text(
                    text = "${status.itemCount} NEW",
                    style = RadarType.micro,
                    color = RadarColors.ink3,
                )
            }
        }
        SoftRule()
    }
}

@Composable
private fun StatusDot(status: FeedStatus) {
    val color =
        when {
            status.neverSynced -> RadarColors.ruleDot
            status.ok -> Ok
            else -> CategoryColors.Vermilion
        }
    Box(
        Modifier
            .size(7.dp)
            .background(color),
    )
}

@Composable
private fun CountRow(
    left: String,
    right: Int,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = left,
                style = RadarType.body,
                color = RadarColors.ink,
                maxLines = 1,
            )
            Text(text = right.toString(), style = RadarType.monoSmall, color = RadarColors.ink2)
        }
        SoftRule()
    }
}

private val Ok = Color(0xFF2F6B3A)

private fun ago(at: Long): String {
    if (at <= 0L) return "NEVER"
    val delta = System.currentTimeMillis() - at
    return when {
        delta < 60_000L -> "NOW"
        delta < 3_600_000L -> "${delta / 60_000L}M"
        delta < 86_400_000L -> "${delta / 3_600_000L}H"
        else -> "${delta / 86_400_000L}D"
    }
}
