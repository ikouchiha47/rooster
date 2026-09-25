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
import com.personalos.app.data.Conversation
import com.personalos.app.data.ConversationRepository
import com.personalos.app.ui.common.CategorySpine
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SectionHeader
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How many conversations the bounded Recent section shows. */
private const val RECENT_LIMIT = 6

/** One keyset page of the All section. */
private const val CONVERSATION_PAGE_SIZE = 200

/**
 * Rows kept in memory for the All section. [mergeArrivals] drops the oldest
 * beyond this, never the newest, so a busy inbox cannot grow without bound.
 */
private const val CONVERSATIONS_CAP = 1_000

private val CONV_TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

private sealed interface ConversationRow {
    data class Section(
        val key: String,
        val title: String,
        val note: String?,
    ) : ConversationRow

    data class Item(
        val key: String,
        val conversation: Conversation,
    ) : ConversationRow
}

/**
 * The Messages body when conversations are shown: a bounded **Recent** section
 * (recency) over a paged **All** section (alphabetical by sender).
 *
 * Two reads, not one, deliberately: recency cannot be cursored — it moves when a
 * message arrives — so only the bounded head of it is read; the complete,
 * stable-by-sender list is what carries a cursor. A new message re-reads the
 * recent head and **merges** it into the already-loaded All list, so the list
 * does not blank and refill.
 */
@Composable
fun ConversationsBody(
    repository: ConversationRepository,
    mode: String,
    smsMaxId: Long?,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var recent by remember(mode) { mutableStateOf<List<Conversation>>(emptyList()) }
    var all by remember(mode) { mutableStateOf<List<Conversation>>(emptyList()) }
    var cursor by remember(mode) { mutableStateOf<String?>(null) }
    var endReached by remember(mode) { mutableStateOf(false) }
    var loading by remember(mode) { mutableStateOf(false) }

    // A tab change is a different list, so replacing it is correct.
    LaunchedEffect(mode) {
        loading = true
        recent = repository.recent(mode, RECENT_LIMIT)
        val first = repository.all(mode, cursor = null, limit = CONVERSATION_PAGE_SIZE)
        all = first
        cursor = first.lastOrNull()?.sender
        endReached = first.size < CONVERSATION_PAGE_SIZE
        loading = false
    }

    // A message landed: same list plus rows. Merge instead of blanking.
    LaunchedEffect(smsMaxId, mode) {
        if (smsMaxId == null) return@LaunchedEffect
        recent = repository.recent(mode, RECENT_LIMIT)
        val fresh = repository.all(mode, cursor = null, limit = CONVERSATION_PAGE_SIZE)
        all =
            mergeArrivals(
                current = all,
                arrivals = fresh,
                key = { it.sender },
                newestFirst = CONVERSATION_ALL_ORDER,
                cap = CONVERSATIONS_CAP,
            )
    }

    // The next page when the list nears its end. The cursor is the last sender
    // of the All section, which is stable under writes.
    LaunchedEffect(listState, mode) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { lastVisible ->
            val total = all.size
            if (!loading && !endReached && total > 0 && lastVisible >= total - 5) {
                loading = true
                val next = repository.all(mode, cursor, CONVERSATION_PAGE_SIZE)
                if (next.isEmpty()) {
                    endReached = true
                } else {
                    all = all + next.filterNot { row -> all.any { it.sender == row.sender } }
                    cursor = next.last().sender
                    if (next.size < CONVERSATION_PAGE_SIZE) endReached = true
                }
                loading = false
            }
        }
    }

    val sections = remember(recent, all) { conversationSections(recent, all, RECENT_LIMIT) }

    val rows =
        remember(sections) {
            buildList {
                if (sections.recent.isNotEmpty()) {
                    add(ConversationRow.Section("s-recent", "Recent", sections.recent.size.toString()))
                    sections.recent.forEach { add(ConversationRow.Item("r-${it.sender}", it)) }
                }
                if (sections.all.isNotEmpty()) {
                    add(ConversationRow.Section("s-all", "All", sections.all.size.toString()))
                    sections.all.forEach { add(ConversationRow.Item("a-${it.sender}", it)) }
                }
            }
        }

    if (rows.isEmpty()) {
        EmptyConversations(modifier.fillMaxSize())
        return
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(
            items = rows,
            key = { row ->
                when (row) {
                    is ConversationRow.Section -> row.key
                    is ConversationRow.Item -> row.key
                }
            },
        ) { row ->
            when (row) {
                is ConversationRow.Section -> SectionHeader(title = row.title, accent = CategoryColors.Teal, note = row.note)
                is ConversationRow.Item ->
                    ConversationRowItem(
                        conversation = row.conversation,
                        onClick = { onOpenConversation(row.conversation.sender) },
                    )
            }
        }
        item { FooterStrip("${all.size} conversations ${Chars.MIDDLE_DOT} grouped by sender") }
    }
}

@Composable
private fun ConversationRowItem(
    conversation: Conversation,
    onClick: () -> Unit,
) {
    val unread = conversation.inboxCount
    val time = remember(conversation.lastAt) { CONV_TIME_FMT.format(Date(conversation.lastAt)) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (unread > 0) RadarColors.fresh else RadarColors.paper2)
                .clickable(onClick = onClick)
                .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategorySpine(if (unread > 0) CategoryColors.Vermilion else Color.Transparent)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversationLabel(conversation),
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
private fun EmptyConversations(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "NO CONVERSATIONS", style = RadarType.micro, color = RadarColors.ink3)
    }
}
