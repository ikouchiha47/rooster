package com.personalos.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.personalos.app.ui.common.SearchField
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    var sims by remember { mutableStateOf<List<com.personalos.app.core.sms.Sim>>(emptyList()) }
    var selectedSim by remember(sender) { mutableStateOf(container.simPreference.selected()) }
    androidx.compose.runtime.LaunchedEffect(sender) {
        sims = runCatching { container.sims.active() }.getOrDefault(emptyList())
    }

    var items by remember(sender) { mutableStateOf<List<EventEntity>>(emptyList()) }
    var cursorTs by remember(sender) { mutableStateOf<Long?>(null) }
    var cursorId by remember(sender) { mutableStateOf(0L) }
    var endReached by remember(sender) { mutableStateOf(false) }
    var loading by remember(sender) { mutableStateOf(false) }

    // The composer's own state. Kept per sender, so switching threads does not
    // carry a half-typed message into the wrong one.
    var draft by remember(sender) { mutableStateOf("") }
    var sending by remember(sender) { mutableStateOf(false) }
    var status by remember(sender) { mutableStateOf<SendStatus?>(null) }

    fun send() {
        val body = draft.trim()
        if (body.isEmpty() || sending) return
        sending = true
        status = SendStatus.Pending
        scope.launch {
            val settled =
                runCatching { container.smsSender.send(sender, body, selectedSim) }
                    .fold(
                        onSuccess = { SendStatus.of(it) },
                        onFailure = { SendStatus.Failed("the send failed") },
                    )
            status = settled
            // Clear only on success; a failure keeps the text so it can be retried
            // without retyping, with the reason shown under the field.
            if (settled == SendStatus.Sent) draft = ""
            sending = false
        }
    }

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

        // The list takes the space above the composer, so a long thread still
        // reaches its end while the composer stays pinned below.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                Text(
                    text = "No messages from this sender.",
                    style = RadarType.body,
                    color = RadarColors.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(items = items, key = { "t-${it.id}" }) { message ->
                        ThreadMessageRow(message)
                    }
                    item { FooterStrip("${items.size} shown ${Chars.MIDDLE_DOT} $title") }
                }
            }
        }

        ThreadComposer(
            draft = draft,
            sending = sending,
            status = status,
            sims = sims,
            selectedSim = selectedSim,
            onSelectSim = {
                selectedSim = it
                container.simPreference.select(it)
            },
            onDraftChange = {
                draft = it
                // Editing starts a new turn, so the old result no longer applies.
                if (status != null) status = null
            },
            onSend = { send() },
        )
    }
}

/**
 * The thread's reply bar: a field, a send arrow, and the last result. Pinned to
 * the bottom and inset for the keyboard and nav bar, so it is never covered and
 * never covers the last message.
 */
@Composable
private fun ThreadComposer(
    draft: String,
    sending: Boolean,
    status: SendStatus?,
    sims: List<com.personalos.app.core.sms.Sim>,
    selectedSim: Int?,
    onSelectSim: (Int?) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(RadarColors.paper2)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        HardRule()
        SimSelector(sims = sims, selected = selectedSim, onSelect = onSelectSim, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            SearchField(
                hint = "Message ${Chars.MIDDLE_DOT} SMS",
                query = draft,
                onQueryChange = onDraftChange,
                onSubmit = { if (!sending) onSend() },
            )
        }
        if (status != null) {
            val color =
                when (status) {
                    SendStatus.Pending -> RadarColors.ink3
                    SendStatus.Sent -> CategoryColors.Teal
                    is SendStatus.Failed -> CategoryColors.Vermilion
                }
            Text(
                text = status.label,
                style = RadarType.microPlain,
                color = color,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp).padding(bottom = 6.dp),
            )
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
