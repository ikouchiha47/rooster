package com.personalos.app.ui.watchers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.data.work.SyncScheduler
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SectionHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/**
 * Background-job status: one row per registered worker, read-only.
 *
 * Rows come from observing WorkManager for the unique-work names
 * [SyncScheduler] actually schedules — a newly scheduled worker appears as a
 * new spec, never as a new branch. All row decisions (order, words,
 * progress math) live in [JobsModel]; this only lays them out with the same
 * tokens and rules as every other dense list.
 */
@Composable
fun JobsSection(
    ruleIds: List<String>,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val specs =
        remember(ruleIds) {
            SyncScheduler.scheduledJobs() + ruleIds.map { SyncScheduler.ruleBackfillSpec(it) }
        }
    val snapshots by remember(container, specs) { container.workerJobs.watch(specs) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val rows = remember(snapshots) { workersOrder(workerRows(snapshots)) }
    val note = remember(rows) { workersNote(rows) }

    Column(modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Background jobs",
            accent = CategoryColors.Teal,
            note = note,
        )
        if (rows.isEmpty()) {
            Text(
                text = "checking${Chars.ELLIPSIS}",
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        } else {
            rows.forEach { row -> JobRow(row) }
        }
    }
}

/** One worker: label left, honest state right, outcome below, bar only while running with progress. */
@Composable
private fun JobRow(row: WorkerRow) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.label,
                style = RadarType.label,
                color = RadarColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = row.stateText,
                style = RadarType.monoSmall,
                color = RadarColors.ink3,
                maxLines = 1,
            )
        }
        if (row.detailText != null) {
            Text(
                text = row.detailText,
                style = RadarType.microPlain,
                color = RadarColors.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Flat 3dp bar on the paper track: the same spine language as the
        // rows, not a Material widget (the paper bans them).
        if (row.fraction != null) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(RadarColors.paper4),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(row.fraction)
                            .height(3.dp)
                            .background(CategoryColors.Teal),
                )
            }
        }
    }
    SoftRule()
}
