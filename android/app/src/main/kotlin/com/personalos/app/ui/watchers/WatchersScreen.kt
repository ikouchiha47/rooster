package com.personalos.app.ui.watchers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.RuleFire
import com.personalos.app.data.RuleWithFires
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SectionHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Watchers: every rule and what it caught.
 *
 * A fire is shown with **what matched** — the clause and the stored value
 * (`temp_c 40.2 > 40`) — not just that something fired, because "Hot day" alone
 * tells you nothing you did not already write.
 *
 * Rules come from the store and fires are read through the same fact-loading
 * ingest uses, so a fire's reason here cannot disagree with the evaluation that
 * produced it. A rule with no fires still appears, saying so.
 */
@Composable
fun WatchersScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val database = remember { AppDatabase.getInstance(context) }
    val maxId by database.eventDao().observeMaxId().collectAsStateWithLifecycle(initialValue = null)
    val rules by container.ruleRepository.observe().collectAsStateWithLifecycle(initialValue = emptyList())

    var groups by remember { mutableStateOf<List<RuleWithFires>>(emptyList()) }
    LaunchedEffect(rules, maxId) {
        groups = runCatching { container.ruleFires.watchers() }.getOrDefault(emptyList())
    }
    val ordered = remember(groups) { watchersOrder(groups) }
    val header = remember(ordered) { watchersHeader(ordered) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper2),
    ) {
        RadarHeader(
            title = "Watchers",
            accent = CategoryColors.Teal,
            onBack = onBack,
            subtitle = {
                Text(
                    text = header,
                    style = RadarType.labelMicro,
                    color = RadarColors.paper4,
                )
            },
        )

        if (groups.isEmpty()) {
            Text(
                text = "No rules yet.",
                style = RadarType.body,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(ordered, key = { "rule-${it.rule.id}" }) { group ->
                RuleBlock(group)
            }
            item {
                Box(Modifier.padding(8.dp)) {
                    Text(
                        text = "Fires are appended once at ingest and read here; nothing is re-evaluated.",
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleBlock(group: RuleWithFires) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            title = group.rule.name,
            accent = CategoryColors.Teal,
            note = ruleNote(group),
            onInk = false,
        )
        if (group.fires.isEmpty()) {
            Text(
                text = "No matches yet.",
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        } else {
            group.fires.forEach { FireRow(it) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun FireRow(fire: RuleFire) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = TIME_FMT.format(Date(fire.matchedAt)),
            style = RadarType.monoSmall,
            color = RadarColors.ink3,
            modifier = Modifier.width(44.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .background(CategoryColors.Teal),
        )
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = fire.title,
                style = RadarType.serifTitle,
                color = RadarColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = fire.source,
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The reason: the clause that held, with the value that satisfied it.
            visibleClauses(fire).forEach { clause ->
                Text(
                    text = clause,
                    style = RadarType.monoSmall,
                    color = RadarColors.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    SoftRule()
}
