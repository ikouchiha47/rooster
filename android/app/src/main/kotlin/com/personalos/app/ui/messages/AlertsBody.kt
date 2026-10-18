package com.personalos.app.ui.messages

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.core.time.UserZone
import com.personalos.app.data.AlertMatch
import com.personalos.app.data.RuleFireRepository
import com.personalos.app.ui.common.CategorySpine
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/** Rows kept in memory; a merge drops the oldest beyond this, never the newest. */
private const val ALERTS_CAP = 1_000

/**
 * The ordering the store uses (`ORDER BY e.timestamp DESC, e.id DESC`), so a
 * merge cannot disagree with the query about which row is "first". `event.id` is
 * the autoincrement row id, the same key the query orders by — not the ULID.
 */
private val ALERT_ORDER: Comparator<AlertMatch> =
    compareByDescending<AlertMatch> { it.itemTimestamp }.thenByDescending { it.event.id }

/**
 * The **Alerts** list: one row per matched event, newest first, across every
 * source and every rule.
 *
 * Distinct from the category tabs in two ways, both deliberate:
 *  - it is not SMS-scoped — a rule can match a weather reading or a feed item, so
 *    filtering to `sms` would hide exactly the cross-source alerts a rule exists
 *    for;
 *  - it is not a conversation list — a row is one matched event with the rule(s)
 *    that matched it, so the reason is visible on the row itself.
 *
 * Reads through [RuleFireRepository.alerts], the same stored matches Watchers
 * shows, so a fire's "what matched" here cannot disagree with ingest. A page is
 * keyset-paged on `(timestamp, event id)`, and new matches (the count changing)
 * merge in rather than blanking the list.
 */
@Composable
internal fun AlertsListBody(
    repository: RuleFireRepository,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var alerts by remember { mutableStateOf<List<AlertMatch>>(emptyList()) }
    var cursorTs by remember { mutableStateOf<Long?>(null) }
    var cursorId by remember { mutableStateOf<Long?>(null) }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }

    // Any written match changes this count; it is the re-read signal. Scoped to
    // Alerts, so a category tab's SMS ping does not wake this list (and vice
    // versa). Remembered so a recomposition does not resubscribe to a new Flow.
    val countFlow = remember(repository) { repository.observeAlertCount() }
    val matchCount by countFlow.collectAsStateWithLifecycle(initialValue = 0)

    LaunchedEffect(repository) {
        loading = true
        val first = repository.alerts(cursorTs = null, cursorId = 0L, limit = RuleFireRepository.ALERT_PAGE_SIZE)
        alerts = first.alerts
        cursorTs = first.nextTs
        cursorId = first.nextId
        endReached = !first.hasMore
        loading = false
        loadedOnce = true
    }

    // A match arrived: merge the newest page in, so composed rows survive. Unlike
    // the category list this is keyed off the match count, not the SMS max id,
    // because Alerts is cross-source.
    LaunchedEffect(matchCount) {
        if (!loadedOnce) return@LaunchedEffect
        val fresh = repository.alerts(cursorTs = null, cursorId = 0L, limit = RuleFireRepository.ALERT_PAGE_SIZE)
        alerts =
            mergeArrivals(
                current = alerts,
                arrivals = fresh.alerts,
                key = { it.itemId },
                newestFirst = ALERT_ORDER,
                cap = ALERTS_CAP,
            )
    }

    // The next page when the list nears its end. The cursor is the last event's
    // `(timestamp, id)`, stable under new rows.
    LaunchedEffect(listState, repository) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            if (loading ||
                endReached ||
                alerts.isEmpty() ||
                lastVisible < alerts.size - 5
            ) {
                return@collect
            }
            loading = true
            val next = repository.alerts(cursorTs, cursorId ?: 0L, RuleFireRepository.ALERT_PAGE_SIZE)
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
    if (ordered.isEmpty()) {
        AlertsEmpty(modifier.fillMaxSize())
        return
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(items = ordered, key = { "alert-${it.itemId}" }) { alert ->
            AlertRow(alert)
        }
        item { FooterStrip("${ordered.size} alerts ${Chars.MIDDLE_DOT} matched events") }
    }
}

/**
 * One Alerts row: the matched item rendered like a message — a sender avatar,
 * the title, the date via [formatMessageTime] — plus the rule(s) that matched it
 * and the clauses that held, so the row says *why* it is here.
 */
@Composable
private fun AlertRow(alert: AlertMatch) {
    val event = alert.event
    val time =
        remember(event.timestamp) {
            formatMessageTime(System.currentTimeMillis(), event.timestamp, UserZone.current)
        }
    // The stored sender/title is the fallback; a known contact name overlays it.
    val label = LocalAppContainer.current.contactNames.nameFor(event.title) ?: event.title

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A match has no unread state, so the spine is a plain divider column.
        CategorySpine(Color.Transparent)
        ConversationAvatar(label = label)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = time, style = RadarType.monoSmall, color = RadarColors.ink3)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = event.source,
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The reason: one chip per matching rule, free text in between rules
            // and their clauses. Nothing here recomputes a match; it is all read.
            alert.rules.forEach { rule ->
                Spacer(Modifier.height(3.dp))
                Chip(text = rule.name, color = CategoryColors.Teal)
                rule.clauses.forEach { clause ->
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
