package com.personalos.app.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personalos.app.core.Chars
import com.personalos.app.data.AppDatabase
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.StatusBarIconsFor
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch

/**
 * Overview: the health of every feed, plus what the store actually holds.
 *
 * This screen only reads and manually triggers the ingest - all fetching lives
 * in FeedIngestor, reached here the same way the background worker reaches it.
 */
@Composable
fun OverviewScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val statuses by container.feeds.statuses.collectAsState()
    val sourceStatuses by container.feeds.sourceStatuses.collectAsState()
    val allStatuses = statuses + sourceStatuses

    val database = remember { AppDatabase.getInstance(context) }
    val totalItems by database.eventDao().observeCount().collectAsState(initial = 0)
    val tagCounts by database.itemTagDao().observeTagCounts().collectAsState(initial = emptyList())
    val sourceCounts by database.eventDao().observeSourceCounts().collectAsState(initial = emptyList())
    val sources by container.sourceRepository.observe().collectAsState(initial = emptyList())
    val syncRuns by database.syncRunDao().observeLatestPerSource().collectAsState(initial = emptyList())

    var syncing by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf<String?>(null) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper),
    ) {
        // This screen's title row is bespoke (no shared header), so it keeps
        // the same rule inline: the paper ground runs up under the cutout
        // while the row content holds the status-bar inset.
        StatusBarIconsFor(RadarColors.paper)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Overview", style = RadarType.serifH1, color = RadarColors.ink)
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
                                // And the party registry: same sync path the yearly
                                // worker runs, so manual and automatic never drift.
                                runCatching { container.partySync.syncAll() }
                                syncing = false
                            }
                        }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        HardRule()

        // Resolve a run's source to the name a person reads. The run stores
        // `source_id`: a ULID for a user row, `seed:search:…` for a seed —
        // identities, not labels. Radar's Events panel resolves the same fact,
        // and two views must not disagree about what to call it.
        val nameById = remember(sources) { sources.associate { it.id to it.name } }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    text =
                        "${compactNumber(allStatuses.size)} FEEDS ${Chars.MIDDLE_DOT} " +
                            "${compactNumber(totalItems)} ITEMS ${Chars.MIDDLE_DOT} " +
                            "${compactNumber(tagCounts.size)} TAGS",
                    style = RadarType.micro,
                    color = RadarColors.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            // The per-kind log the Events tab reads, management-flavoured: one
            // row per kind that ever ran, newest run only. Feeds the many, so
            // both views over one fact cannot drift.
            item { WidgetHeader(title = "Sync activity", note = "${syncRuns.size} sources") }
            items(syncRunKinds(syncRuns), key = { "kind-${it.kind}" }) { run ->
                SyncRunRow(run, label = runSourceLabel(run, nameById))
            }

            item { SectionGap() }
            item {
                WidgetHeader(
                    title = "Feeds",
                    note = "${allStatuses.count { it.ok }}/${allStatuses.size} ok",
                )
            }
            changing?.let { editingId ->
                item {
                    CategoryChooser(
                        name = allStatuses.firstOrNull { it.id == editingId }?.name.orEmpty(),
                        current = allStatuses.firstOrNull { it.id == editingId }?.tags?.firstOrNull(),
                        categories = container.feedCategories,
                        onPick = { category ->
                            changing = null
                            scope.launch {
                                runCatching { container.sourceRepository.updateTags(editingId, setOf(category)) }
                                runCatching { container.retagger.run() }
                            }
                        },
                        onCancel = { changing = null },
                    )
                }
            }
            items(allStatuses, key = { it.id }) { status -> FeedRow(status, onCategoryTap = { changing = status.id }) }

            item { SectionGap() }
            item {
                WidgetHeader(
                    title = "Inventory",
                    note = "${tagCounts.size} tags ${Chars.MIDDLE_DOT} ${sourceCounts.size} sources",
                )
            }
            item { MicroLabel("BY TAG") }
            items(tagCounts, key = { "tag-${it.tag}" }) { row ->
                CountRow(left = row.tag, right = row.count)
            }
            item { MicroLabel("BY SOURCE") }
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

/** The tap-to-change hatch: pick the category a feed declares. */
@Composable
private fun CategoryChooser(
    name: String,
    current: String?,
    categories: List<String>,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "CATEGORY FOR ${name.uppercase()}",
                style = RadarType.micro,
                color = RadarColors.ink3,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "CANCEL",
                style = RadarType.micro,
                color = RadarColors.ink3,
                modifier =
                    Modifier
                        .clickable { onCancel() }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
        ) {
            categories.forEach { candidate ->
                val selected = candidate == current
                Text(
                    text = candidate,
                    style = RadarType.micro,
                    color = if (selected) RadarColors.paper else RadarColors.ink,
                    modifier =
                        Modifier
                            .padding(end = 6.dp)
                            .background(if (selected) RadarColors.ink else RadarColors.paper, RoundedCornerShape(2.dp))
                            .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
                            .clickable { onPick(candidate) }
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MicroLabel(text: String) {
    Text(
        text = text,
        style = RadarType.micro,
        color = RadarColors.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * What to call a run's source: its stored name when it is a registry row, the
 * region's readable name when it is a calendar (the slug `india/west-bengal`
 * reads as `West Bengal`), and the raw id only as a last resort.
 *
 * Pure, so the naming rule is testable rather than scattered through the row.
 */
internal fun runSourceLabel(
    run: com.personalos.app.data.SyncRunEntity,
    nameById: Map<String, String>,
): String {
    val name = nameById[run.sourceId] ?: return run.sourceId
    return if (run.kind == "calendar") {
        com.personalos.app.core.calendar.CalendarDirectoryParser
            .displayName(name)
    } else {
        name
    }
}

/**
 * One row per kind that has ever run: the most recent run of that kind. Kinds
 * are ordered the way the services read (feeds, topics, sms, weather, fx,
 * device); a kind with no run yet simply has no row.
 */
private val SYNC_KIND_ORDER = listOf("rss", "search", "sms", "weather", "fx", "device", "calendar")

internal fun syncRunKinds(runs: List<com.personalos.app.data.SyncRunEntity>): List<com.personalos.app.data.SyncRunEntity> {
    val latestByKind = runs.groupBy { it.kind }.mapValues { (_, group) -> group.maxBy { it.finishedAt } }
    val known =
        SYNC_KIND_ORDER.mapNotNull { kind -> latestByKind[kind] }
    val extra =
        latestByKind
            .filterKeys { it !in SYNC_KIND_ORDER }
            .values
            .sortedByDescending { it.finishedAt }
    return known + extra
}

internal fun syncKindLabel(kind: String): String =
    when (kind) {
        "rss" -> "Feeds"
        "search" -> "Topics"
        "sms" -> "SMS"
        "weather" -> "Weather"
        "fx" -> "FX"
        "device" -> "Device"
        "calendar" -> "Calendar"
        else -> kind
    }

@Composable
private fun SyncRunRow(
    run: com.personalos.app.data.SyncRunEntity,
    label: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (run.ok) Ok else CategoryColors.Vermilion),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = syncKindLabel(run.kind),
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 1,
                )
                Text(
                    text = run.error ?: "$label ${Chars.MIDDLE_DOT} ${ago(run.finishedAt)}",
                    style = RadarType.microPlain,
                    color = if (run.ok) RadarColors.ink3 else CategoryColors.Vermilion,
                    maxLines = 1,
                )
            }
            Text(
                text = if (run.ok) "+${compactNumber(run.itemsAdded)}" else "FAIL",
                style = RadarType.monoSmall,
                color = if (run.ok) RadarColors.ink2 else CategoryColors.Vermilion,
            )
        }
        SoftRule()
    }
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
