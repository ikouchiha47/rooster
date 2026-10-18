package com.personalos.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.EventEntity
import com.personalos.app.data.SmsSource
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.HeaderMeta
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val THREAD_PAGE_SIZE = 200

private val THREAD_TIME_FMT = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

/** The same ordering the thread query uses: newest publish, then newest id. */
private val THREAD_NEWEST_FIRST: Comparator<EventEntity> =
    compareByDescending<EventEntity> { it.timestamp }.thenByDescending { it.id }

/**
 * One sender's thread: the messages behind a conversation row.
 *
 * It pages with the same `(timestamp, id)` keyset as the flat timeline and is
 * scoped `source = 'sms' AND title = :sender` in SQL, so a thread can only ever
 * show that sender's SMS. A new arrival merges rather than blanking the list.
 *
 * The title is the stored address, overlaid with the contact's name when one is
 * known; with no contacts permission it is the address verbatim.
 */
@Composable
fun ConversationThreadScreen(
    sender: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val repository = container.conversations
    val listState = rememberLazyListState()

    var items by remember(sender) { mutableStateOf<List<EventEntity>>(emptyList()) }
    var cursorTs by remember(sender) { mutableStateOf<Long?>(null) }
    var cursorId by remember(sender) { mutableStateOf(0L) }
    var endReached by remember(sender) { mutableStateOf(false) }
    var loading by remember(sender) { mutableStateOf(false) }

    val smsMaxId by remember { AppDatabase.getInstance(context) }
        .messageSearchDao()
        .observeMaxIdForSource(SmsSource.SOURCE_ID)
        .collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(sender) {
        loading = true
        val first = repository.thread(sender, null, 0L, THREAD_PAGE_SIZE)
        items = first
        if (first.isNotEmpty()) {
            cursorTs = first.last().timestamp
            cursorId = first.last().id
        }
        endReached = first.size < THREAD_PAGE_SIZE
        loading = false
    }

    LaunchedEffect(smsMaxId) {
        if (smsMaxId == null) return@LaunchedEffect
        val fresh = repository.thread(sender, null, 0L, THREAD_PAGE_SIZE)
        items =
            mergeArrivals(
                current = items,
                arrivals = fresh,
                key = { it.ulid },
                newestFirst = THREAD_NEWEST_FIRST,
                cap = 1_000,
            )
    }

    LaunchedEffect(listState, sender) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            if (!loading && !endReached && items.isNotEmpty() && lastVisible >= items.size - 5) {
                loading = true
                val next = repository.thread(sender, cursorTs, cursorId, THREAD_PAGE_SIZE)
                if (next.isEmpty()) {
                    endReached = true
                } else {
                    items = items + next
                    cursorTs = next.last().timestamp
                    cursorId = next.last().id
                    if (next.size < THREAD_PAGE_SIZE) endReached = true
                }
                loading = false
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(RadarColors.paper2)) {
        // The stored sender is the fallback; a known contact name overlays it.
        val title = container.contactNames.nameFor(sender) ?: sender
        RadarHeader(
            title = title,
            accent = RadarColors.ink,
            onBack = onBack,
            trailing = {
                HeaderMeta(
                    "${compactNumber(items.size)} messages ${Chars.MIDDLE_DOT} SMS only",
                    Modifier.padding(end = 8.dp),
                )
            },
        )

        if (items.isEmpty()) {
            Text(
                text = "No messages from this sender.",
                style = RadarType.body,
                color = RadarColors.ink3,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            )
            return@Column
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items = items, key = { "t-${it.id}" }) { message ->
                ThreadMessageRow(message)
            }
            item { FooterStrip("${items.size} shown ${Chars.MIDDLE_DOT} $title") }
        }
    }
}

/** All messages in a thread share the sender, so the row leads with time and state. */
@Composable
private fun ThreadMessageRow(message: EventEntity) {
    val time = remember(message.timestamp) { THREAD_TIME_FMT.format(Date(message.timestamp)) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = time, style = RadarType.monoSmall, color = RadarColors.ink3, modifier = Modifier.width(96.dp))
            Spacer(Modifier.width(6.dp))
            Chip(text = message.type.uppercase(), color = threadStateColor(message.type))
        }
        Text(
            text = message.content,
            style = RadarType.small,
            color = RadarColors.ink,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp),
        )
        HardRule()
    }
}

/** Box state as a chip colour, mirroring the timeline row's vocabulary. */
private fun threadStateColor(type: String): Color =
    when (type) {
        "inbox" -> CategoryColors.Vermilion
        "sent", "queued", "outbox" -> CategoryColors.Chartreuse
        else -> RadarColors.paper4
    }
