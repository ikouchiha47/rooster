package com.personalos.app.ui.messages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.EventEntity
import com.personalos.app.data.SmsSource
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SearchField
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.common.groupIntoDays
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val THREAD_PAGE_SIZE = 200

private val THREAD_CLOCK_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

private val THREAD_DAYTIME_FMT = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

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
    onOpenSettings: () -> Unit = {},
    onSearchThread: () -> Unit = {},
    onMute: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val bgStore = remember { ThreadBackgroundStore(context) }
    var background by remember { mutableStateOf(bgStore.get()) }
    val muteStore = remember { MuteStore(context) }
    var muted by remember(sender) { mutableStateOf(muteStore.isMuted(sender)) }
    var searching by remember(sender) { mutableStateOf(false) }
    var searchQuery by remember(sender) { mutableStateOf("") }
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
        snapshotFlow { listState.firstVisibleItemIndex }.collect { firstVisible ->
            if (!loading && !endReached && items.isNotEmpty() && firstVisible <= 5) {
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
            title = title.take(50),
            accent = RadarColors.ink,
            onBack = onBack,
            subtitle = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != sender) {
                        Text(
                            text = sender,
                            style = RadarType.microPlain,
                            color = RadarColors.ink,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = "${compactNumber(items.size)} messages",
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                    )
                    if (muted) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "MUTED",
                            style = RadarType.microPlain,
                            color = RadarColors.paper2,
                            modifier =
                                Modifier
                                    .background(RadarColors.ink)
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    HeaderIcon(Icons.Outlined.Phone, "Call") {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(sender)}")),
                            )
                        }
                    }
                    HeaderIcon(Icons.Outlined.Search, "Search") { searching = !searching }
                    HeaderIcon(Icons.Outlined.NotificationsOff, if (muted) "Unmute" else "Mute") {
                        muted = !muted
                        muteStore.setMuted(sender, muted)
                    }
                    HeaderIcon(Icons.Outlined.Tune, "Settings", onOpenSettings)
                }
            },
        )

        if (searching) {
            SearchField(
                hint = "Search in thread",
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        val ordered =
            remember(items, searchQuery) {
                val all = items.asReversed()
                if (searchQuery.isBlank()) all else all.filter { it.content.contains(searchQuery, ignoreCase = true) }
            }
        val rows =
            remember(ordered) {
                buildList {
                    for (group in groupIntoDays(ordered) { it.timestamp }) {
                        add(ThreadRow.Day(group.dayStart))
                        group.items.forEach { add(ThreadRow.Message(it)) }
                    }
                }
            }
        var didInitialScroll by remember(sender) { mutableStateOf(false) }
        LaunchedEffect(rows.size, didInitialScroll) {
            if (!didInitialScroll && rows.isNotEmpty()) {
                listState.scrollToItem(rows.lastIndex)
                didInitialScroll = true
            }
        }

        // The list takes the space above the composer, so a long thread still
        // reaches its end while the composer stays pinned below.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (background == ThreadBackground.DOTTED) {
                DottedBackground(Modifier.matchParentSize())
            }
            if (items.isEmpty()) {
                Text(
                    text = "No messages from this sender.",
                    style = RadarType.body,
                    color = RadarColors.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            } else {
                // Bottom-anchored, like every chat: with few messages they sit
                // against the composer rather than stranded at the top.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    rows.forEach { row ->
                        when (row) {
                            is ThreadRow.Day -> item(key = "d-${row.start}") { DaySeparator(row.start) }
                            is ThreadRow.Message -> item(key = "m-${row.message.id}") { MessageBubble(row.message) }
                        }
                    }
                    item {
                        val lastSent = items.firstOrNull { it.type == "sent" }
                        FooterStrip(
                            text =
                                lastSent?.let {
                                    "sent ${THREAD_DAYTIME_FMT.format(Date(it.timestamp))}"
                                } ?: "no messages sent",
                        )
                    }
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(
                            min = 48.dp,
                            max = (LocalConfiguration.current.screenHeightDp * 0.3f).dp,
                        ).background(RadarColors.paper)
                        .border(1.dp, RadarColors.ink)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                textStyle = RadarType.small.copy(color = RadarColors.ink),
                cursorBrush = SolidColor(RadarColors.ink),
                singleLine = false,
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(
                            text = "Message ${Chars.MIDDLE_DOT} SMS",
                            style = RadarType.small,
                            color = RadarColors.ink3,
                        )
                    }
                    inner()
                },
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(RadarColors.ink, RoundedCornerShape(6.dp))
                        .clickable { if (!sending && draft.isNotBlank()) onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "\u2191", style = RadarType.serifH3, color = RadarColors.paper2)
            }
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

/** One row in the chat: a day separator or a message bubble. */
private sealed interface ThreadRow {
    data class Day(
        val start: Long,
    ) : ThreadRow

    data class Message(
        val message: EventEntity,
    ) : ThreadRow
}

/** A day divider, centred and quiet, so it separates without shouting. */
@Composable
private fun DaySeparator(start: Long) {
    Text(
        text = dayLabel(start).uppercase(),
        style = RadarType.micro,
        color = RadarColors.ink3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
}

/** One chat bubble: incoming on paper, outgoing on ink, with the time inside. */
@Composable
private fun MessageBubble(message: EventEntity) {
    val outgoing = message.type == "sent" || message.type == "queued" || message.type == "outbox"
    val time = remember(message.timestamp) { THREAD_CLOCK_FMT.format(Date(message.timestamp)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 284.dp)
                    .background(if (outgoing) RadarColors.ink else RadarColors.paper, bubbleShape(outgoing))
                    .border(1.dp, if (outgoing) RadarColors.ink else RadarColors.ruleSoft, bubbleShape(outgoing))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = message.content,
                style = RadarType.small,
                color = if (outgoing) RadarColors.paper2 else RadarColors.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (outgoing) "$time ${Chars.MIDDLE_DOT} sent" else time,
                style = RadarType.microPlain,
                color = if (outgoing) RadarColors.paper4 else RadarColors.ink3,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** A speech-bubble corner: the tail sits on the sender's side. */
private fun bubbleShape(outgoing: Boolean) =
    RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = if (outgoing) 12.dp else 2.dp,
        bottomEnd = if (outgoing) 2.dp else 12.dp,
    )

/** A 32dp hit area with a 22dp Material icon — one consistent optical box. */
@Composable
private fun HeaderIcon(
    image: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(32.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = description,
            tint = RadarColors.paper2,
            modifier = Modifier.size(22.dp),
        )
    }
}
