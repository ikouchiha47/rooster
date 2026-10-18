package com.personalos.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.personalos.app.core.Chars
import com.personalos.app.core.time.UserZone
import com.personalos.app.data.Conversation
import com.personalos.app.data.ConversationRepository
import com.personalos.app.ui.common.CategorySpine
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.DayMarker
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.common.dayLabelRight
import com.personalos.app.ui.common.groupIntoDays
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/** One page of the conversation list. */
private const val CONVERSATION_PAGE_SIZE = 200

/** Rows kept in memory; a merge drops the oldest beyond this, never the newest. */
private const val CONVERSATIONS_CAP = 1_000

/** The flattened shape: an optional day header, then that day's senders. */
private sealed interface ConversationListRow {
    data class Header(
        val dayStart: Long,
    ) : ConversationListRow

    data class Item(
        val conversation: Conversation,
    ) : ConversationListRow
}

/**
 * The **one** conversation list both Messages views render: one row per sender,
 * newest [Conversation.lastAt] first.
 *
 * [groupByDay] is the only difference between the two views — the Timeline
 * buckets rows under day markers, the flat view leaves them bare. Everything
 * else (the recency read, the `(lastAt, sender)` keyset, paging and merging) is
 * shared, so the two cannot drift into different orderings.
 *
 * Both views, and only these views, read [ConversationRepository.timeline]; each
 * conversation appears under the single tab of its latest message's category.
 * Saved and search list messages, not conversations, and stay flat on their own
 * path in [MessagesScreen].
 *
 * Rows reuse [ConversationRowItem], so a conversation renders identically in
 * both views.
 */
@Composable
internal fun ConversationsListBody(
    repository: ConversationRepository,
    categoryId: String,
    smsMaxId: Long?,
    categoryVersion: String?,
    groupByDay: Boolean,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var conversations by remember(categoryId) { mutableStateOf<List<Conversation>>(emptyList()) }
    var cursorAt by remember(categoryId) { mutableStateOf<Long?>(null) }
    var cursorSender by remember(categoryId) { mutableStateOf<String?>(null) }
    var endReached by remember(categoryId) { mutableStateOf(false) }
    var loading by remember(categoryId) { mutableStateOf(false) }

    // A tab change is a different list, so replacing it is correct.
    LaunchedEffect(categoryId) {
        loading = true
        val first = repository.timeline(categoryId, cursorAt = null, cursorSender = null, limit = CONVERSATION_PAGE_SIZE)
        conversations = conversationTimeline(first)
        cursorAt = first.lastOrNull()?.lastAt
        cursorSender = first.lastOrNull()?.sender
        endReached = first.size < CONVERSATION_PAGE_SIZE
        loading = false
    }

    // The same list with updated rows, on either signal: a message landed, or
    // the re-classify walk rewrote a latest message's category. Merge instead of
    // blanking, so composed rows survive. `smsMaxId` starts null; skip until a
    // real read is possible.
    LaunchedEffect(smsMaxId, categoryId, categoryVersion) {
        if (smsMaxId == null) return@LaunchedEffect
        val fresh = repository.timeline(categoryId, cursorAt = null, cursorSender = null, limit = CONVERSATION_PAGE_SIZE)
        conversations =
            mergeArrivals(
                current = conversations,
                arrivals = fresh,
                key = { it.sender },
                newestFirst = CONVERSATION_TIMELINE_ORDER,
                cap = CONVERSATIONS_CAP,
            )
    }

    // The rows. `groupIntoDays` is order-preserving, so the day buckets cannot
    // reorder what the keyset paged; the flat case is that same list, unbucketed.
    val rows =
        remember(conversations, groupByDay) {
            buildList {
                if (groupByDay) {
                    for (group in groupIntoDays(conversations) { it.lastAt }) {
                        add(ConversationListRow.Header(group.dayStart))
                        group.items.forEach { add(ConversationListRow.Item(it)) }
                    }
                } else {
                    conversations.forEach { add(ConversationListRow.Item(it)) }
                }
            }
        }

    // The next page when the list nears its end. The cursor is the last
    // conversation's `(lastAt, sender)`, which is stable under new rows.
    LaunchedEffect(listState, categoryId) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            if (!loading && !endReached && rows.isNotEmpty() && lastVisible >= rows.size - 5) {
                loading = true
                val next = repository.timeline(categoryId, cursorAt, cursorSender, CONVERSATION_PAGE_SIZE)
                if (next.isEmpty()) {
                    endReached = true
                } else {
                    val known = conversations.mapTo(HashSet()) { it.sender }
                    conversations = conversationTimeline(conversations + next.filterNot { it.sender in known })
                    cursorAt = next.last().lastAt
                    cursorSender = next.last().sender
                    if (next.size < CONVERSATION_PAGE_SIZE) endReached = true
                }
                loading = false
            }
        }
    }

    if (rows.isEmpty()) {
        EmptyConversations(modifier.fillMaxSize())
        return
    }

    val footer =
        if (groupByDay) {
            "${conversations.size} conversations ${Chars.MIDDLE_DOT} by date"
        } else {
            "${conversations.size} conversations ${Chars.MIDDLE_DOT} newest first"
        }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(
            items = rows,
            key = { row ->
                when (row) {
                    is ConversationListRow.Header -> "h-${row.dayStart}"
                    is ConversationListRow.Item -> "c-${row.conversation.sender}"
                }
            },
        ) { row ->
            when (row) {
                is ConversationListRow.Header -> {
                    val (label, right) =
                        remember(row.dayStart) {
                            val l = dayLabel(row.dayStart)
                            l to dayLabelRight(l, row.dayStart)
                        }
                    DayMarker(label = label, right = right)
                }

                is ConversationListRow.Item ->
                    ConversationRowItem(
                        conversation = row.conversation,
                        onClick = { onOpenConversation(row.conversation.sender) },
                    )
            }
        }
        item { FooterStrip(footer) }
    }
}

@Composable
internal fun ConversationRowItem(
    conversation: Conversation,
    onClick: () -> Unit,
) {
    val unread = conversation.inboxCount
    val time =
        remember(conversation.lastAt) {
            formatMessageTime(System.currentTimeMillis(), conversation.lastAt, UserZone.current)
        }
    // The stored sender is the fallback; a known contact name overlays it. Not
    // remembered: the resolver is empty at first composition and fills in later.
    val label = LocalAppContainer.current.contactNames.nameFor(conversation.sender) ?: conversationLabel(conversation)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (unread > 0) RadarColors.fresh else RadarColors.paper2)
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategorySpine(if (unread > 0) CategoryColors.Vermilion else Color.Transparent)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = conversationLastMessage(conversation),
                    style = RadarType.small,
                    color = RadarColors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread > 0) {
                    Spacer(Modifier.width(6.dp))
                    Chip(text = unread.toString(), color = CategoryColors.Vermilion)
                }
            }
        }
    }
    HardRule()
}

@Composable
internal fun EmptyConversations(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "NO CONVERSATIONS", style = RadarType.micro, color = RadarColors.ink3)
    }
}
