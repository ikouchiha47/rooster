package com.personalos.app.ui.watchers

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.RuleFire
import com.personalos.app.data.RuleFirePage
import com.personalos.app.data.RuleFireRepository
import com.personalos.app.data.RuleWithFires
import com.personalos.app.ui.common.HeaderMeta
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SectionHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.navigation.Destination
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

/** The day a fire happened, so a feed item is not just an undated clock time. */
private val DAY_FMT = SimpleDateFormat("dd MMM", Locale.getDefault())

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
    onNavigate: (Destination) -> Unit = {},
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
            // The same shape as News, via the one component that owns it.
            trailing = { HeaderMeta(header, Modifier.padding(end = 8.dp)) },
        )

        // Job health sits above the rules: a running or failed job is timely,
        // and this keeps the rules+fires list below exactly as it was.
        JobsSection(ruleIds = rules.map { it.id })

        if (groups.isEmpty()) {
            Text(
                text = "No rules yet.",
                style = RadarType.body,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )
            return@Column
        }

        val scope = rememberCoroutineScope()
        // Per rule: the fires loaded after the first page, the cursor for the next
        // page, whether there is no more, and whether a load is in flight. Keyed by
        // rule id, because a LazyColumn's header and its item list are separate
        // composables that cannot share a `remember` scope.
        val extra = remember { mutableStateMapOf<String, List<RuleFire>>() }
        val cursor = remember { mutableStateMapOf<String, Pair<Long, String>>() }
        val done = remember { mutableStateMapOf<String, Boolean>() }
        val loading = remember { mutableStateMapOf<String, Boolean>() }

        // `weight(1f)`, not `fillMaxSize()`: under a header, fillMaxSize sizes the
        // list to the whole parent, so its bottom hangs off-screen and the last
        // rows cannot be reached. weight takes the space left below the header.
        //
        // Each rule's header is a `stickyHeader`, so it pins to the top while that
        // rule's fires scroll under it; the next rule's header pushes it off.
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            ordered.forEach { group ->
                val id = group.rule.id
                val shown = group.fires + extra[id].orEmpty()
                val isExpanded = extra[id].orEmpty().isNotEmpty()
                val remaining = (group.total - shown.size).coerceAtLeast(0)
                val canLoadMore = remaining > 0 && done[id] != true

                stickyHeader(key = "rule-$id") {
                    // The contract control lives *in the header*: it is sticky, so
                    // "show less" stays reachable while a long expanded section
                    // scrolls, instead of sitting under hundreds of rows.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(RadarColors.paper2)
                            .clickable(enabled = isExpanded) {
                                extra.remove(id)
                                cursor.remove(id)
                                done.remove(id)
                            },
                    ) {
                        SectionHeader(
                            title = group.rule.name,
                            accent = CategoryColors.Teal,
                            note = if (isExpanded) "show less" else ruleNote(group),
                            onInk = false,
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "empty-$id") {
                        Text(
                            text = "No matches yet.",
                            style = RadarType.microPlain,
                            color = RadarColors.ink3,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    items(items = shown, key = { "fire-$id-${it.itemId}" }) { fire ->
                        // The row id resolves on tap, not at list time: one
                        // query per opened fire, never N queries for N rows.
                        FireRow(fire) {
                            scope.launch {
                                val eventId = runCatching { container.ruleFires.eventIdFor(fire.itemId) }.getOrNull()
                                fireDestination(fire.source, fire.title, eventId)?.let(onNavigate)
                            }
                        }
                    }
                    if (canLoadMore) {
                        item(key = "more-$id") {
                            MoreRow(if (loading[id] == true) "loading" else "+$remaining more") {
                                if (loading[id] == true) return@MoreRow
                                // The next page starts after the last fire already shown.
                                val start =
                                    cursor[id]
                                        ?: group.fires.lastOrNull()?.let { it.itemTimestamp to it.itemId }
                                        ?: return@MoreRow
                                loading[id] = true
                                scope.launch {
                                    val page =
                                        runCatching {
                                            container.ruleFires
                                                .forRulePage(id, start.first, start.second, RuleFireRepository.PAGE_SIZE)
                                        }.getOrDefault(RuleFirePage(emptyList(), null, null))
                                    // Append to the *extra* list, not to `shown` (which
                                    // already contains these): re-adding the first page
                                    // is a duplicate key, and a LazyColumn crash.
                                    extra[id] = extra[id].orEmpty() + page.fires
                                    if (page.hasMore && page.nextTs != null && page.nextId != null) {
                                        cursor[id] = page.nextTs to page.nextId
                                    } else {
                                        done[id] = true
                                    }
                                    loading[id] = false
                                }
                            }
                        }
                    }
                }
                item(key = "gap-$id") { Spacer(Modifier.height(6.dp)) }
            }
            item {
                Box(Modifier.padding(8.dp)) {
                    Text(
                        text = "Fires are written at ingest and backfilled for rules added later; read-only here.",
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                    )
                }
            }
        }
    }
}

/** The one tap between a rule's five and the rest of them. */
@Composable
private fun MoreRow(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = RadarType.labelMicro,
        color = CategoryColors.Teal,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun FireRow(
    fire: RuleFire,
    onOpen: () -> Unit = {},
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        // The item's time, not the match's: a backfilled fire is written now while
        // its item may be days old, so the match time would put a whole backfill
        // "today" and sort it as simultaneous.
        Column(modifier = Modifier.width(46.dp)) {
            Text(
                text = TIME_FMT.format(Date(fire.itemTimestamp)),
                style = RadarType.monoSmall,
                color = RadarColors.ink3,
            )
            Text(
                text = DAY_FMT.format(Date(fire.itemTimestamp)),
                style = RadarType.monoSmall,
                color = RadarColors.ink3,
            )
        }
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
