package com.personalos.app.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.time.UserZone
import com.personalos.app.data.AlertMatch
import com.personalos.app.data.AlertScope
import com.personalos.app.data.EventEntity
import com.personalos.app.data.RuleFireRepository
import com.personalos.app.data.SmsSource
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.messages.formatMessageTime
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/** Rows kept in memory; a merge drops the oldest beyond this, never the newest. */
private const val ALERTS_CAP = 1_000

private val ALERT_ORDER: Comparator<AlertMatch> =
    compareByDescending<AlertMatch> { it.itemTimestamp }.thenByDescending { it.event.id }

/**
 * A screen's **Alerts** tab: rule matches scoped to that screen's facts.
 *
 * News passes `AlertScope.forTag(Tags.NEWS)`, M&M `finance`, Travel `travel`.
 * The scope's `sourcePrefix` is the active source filter, so the tab follows
 * the same filter as the content tabs. Messages keeps its own SMS scope.
 *
 * Reads [RuleFireRepository.alerts]; a row is one matched event with every rule
 * that matched it, so the reason travels with the item.
 */
@Composable
fun AlertsTab(
    repository: RuleFireRepository,
    scope: AlertScope,
    modifier: Modifier = Modifier,
    onOpen: (EventEntity) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var alerts by remember { mutableStateOf<List<AlertMatch>>(emptyList()) }
    var cursorTs by remember { mutableStateOf<Long?>(null) }
    var cursorId by remember { mutableStateOf<Long?>(null) }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }

    val countFlow = remember(repository, scope) { repository.observeAlertCount(scope) }
    val matchCount by countFlow.collectAsStateWithLifecycle(initialValue = 0)

    LaunchedEffect(repository, scope) {
        loading = true
        alerts = emptyList()
        val first =
            repository.alerts(
                cursorTs = null,
                cursorId = 0L,
                limit = RuleFireRepository.ALERT_PAGE_SIZE,
                scope = scope,
            )
        alerts = first.alerts
        cursorTs = first.nextTs
        cursorId = first.nextId
        endReached = !first.hasMore
        loading = false
        loadedOnce = true
    }

    LaunchedEffect(matchCount, scope) {
        if (!loadedOnce) return@LaunchedEffect
        val fresh =
            repository.alerts(
                cursorTs = null,
                cursorId = 0L,
                limit = RuleFireRepository.ALERT_PAGE_SIZE,
                scope = scope,
            )
        alerts =
            mergeAlerts(
                current = alerts,
                arrivals = fresh.alerts,
                cap = ALERTS_CAP,
            )
    }

    LaunchedEffect(listState, repository, scope) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            if (loading || endReached || alerts.isEmpty() || lastVisible < alerts.size - 5) {
                return@collect
            }
            loading = true
            val next =
                repository.alerts(
                    cursorTs = cursorTs,
                    cursorId = cursorId ?: 0L,
                    limit = RuleFireRepository.ALERT_PAGE_SIZE,
                    scope = scope,
                )
            if (next.alerts.isEmpty()) {
                endReached = true
            } else {
                val known = alerts.mapTo(HashSet()) { it.itemId }
                alerts = alerts + next.alerts.filterNot { it.itemId in known }
                cursorTs = next.nextTs
                cursorId = next.nextId
                if (!next.hasMore) endReached = true
            }
            loading = false
        }
    }

    val ordered = remember(alerts) { alerts.sortedWith(ALERT_ORDER) }
    if (loadedOnce && ordered.isEmpty()) {
        AlertsEmpty(modifier.fillMaxSize(), loading)
        return
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(items = ordered, key = { "alert-${it.itemId}" }) { alert ->
            AlertRow(alert, onOpen = { onOpen(alert.event) })
        }
        if (ordered.isNotEmpty()) {
            item { FooterStrip("${ordered.size} alerts ${Chars.MIDDLE_DOT} matched events") }
        }
    }
}

private fun mergeAlerts(
    current: List<AlertMatch>,
    arrivals: List<AlertMatch>,
    cap: Int,
): List<AlertMatch> {
    if (arrivals.isEmpty()) return current
    val known = current.mapTo(HashSet()) { it.itemId }
    val merged = current + arrivals.filterNot { it.itemId in known }
    return merged.sortedWith(ALERT_ORDER).take(cap)
}

/**
 * One matched item: title, source, when, and the rule(s) that matched it with
 * the clauses that held. The row itself is the tap target; it opens the same
 * destination as the item in its content tab.
 */
@Composable
private fun AlertRow(
    alert: AlertMatch,
    onOpen: () -> Unit,
) {
    val event = alert.event
    val time = remember(event.timestamp) { formatMessageTime(System.currentTimeMillis(), event.timestamp, UserZone.current) }
    val day = remember(event.timestamp) { dayLabel(dayStart(event.timestamp)) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(48.dp)) {
            Text(text = time, style = RadarType.monoSmall, color = RadarColors.ink3)
            Text(text = day, style = RadarType.monoSmall, color = RadarColors.ink3, maxLines = 1)
        }
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .background(CategoryColors.Teal),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = RadarType.serifTitle,
                color = RadarColors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = event.source,
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            alert.rules.forEach { rule ->
                Spacer(Modifier.height(3.dp))
                Chip(text = rule.name, color = CategoryColors.Teal)
                rule.clauses.take(2).forEach { clause ->
                    Text(
                        text = clause.summary,
                        style = RadarType.monoSmall,
                        color = RadarColors.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    HardRule()
}

@Composable
private fun AlertsEmpty(
    modifier: Modifier,
    loading: Boolean,
) {
    Box(modifier, contentAlignment = Alignment.TopStart) {
        Text(
            text = if (loading) "Loading alerts…" else "No alerts in this view yet.",
            style = RadarType.body,
            color = RadarColors.ink3,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

private fun dayStart(timestamp: Long): Long = timestamp / 86_400_000L * 86_400_000L

/** The same destination rule Watchers uses: SMS opens the thread, a stored row opens the article. */
fun alertDestination(event: EventEntity): com.personalos.app.ui.navigation.Destination? =
    when {
        event.source == SmsSource.SOURCE_ID ->
            com.personalos.app.ui.navigation.Destination
                .Conversation(event.title)
        event.id > 0L ->
            com.personalos.app.ui.navigation.Destination
                .Article(event.id)
        else -> null
    }
