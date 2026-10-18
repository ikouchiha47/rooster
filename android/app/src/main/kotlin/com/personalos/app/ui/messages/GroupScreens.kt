package com.personalos.app.ui.messages

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalos.app.data.sms.GroupRecord
import com.personalos.app.data.sms.taggedBody
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Every broadcast the app has made: one row per tagged send. A group is a
 * broadcast, not a stable conversation yet — so two sends to the same people are
 * two rows, which is honest about what the tag actually carries.
 */
@Composable
fun GroupsScreen(
    onOpenGroup: (String) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    var groups by remember { mutableStateOf(container.groupStore.all()) }

    Surface(modifier = Modifier.fillMaxSize(), color = RadarColors.paper2) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(RadarColors.paper2),
        ) {
            RadarHeader(
                title = "Groups",
                accent = RadarColors.ink,
                onBack = onBack,
                subtitle = {
                    Text(
                        text = "${groups.size} broadcasts",
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                    )
                },
            )
            if (groups.isEmpty()) {
                EmptyGroups(Modifier.fillMaxSize())
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = groups, key = { it.id }) { group ->
                        GroupRow(group = group, onClick = { onOpenGroup(group.id) })
                        HardRule()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGroups(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "NO GROUPS YET", style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Compose to two or more people with the group tag on",
                style = RadarType.small,
                color = RadarColors.ink3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One broadcast: its people, its body, and how much has come back. */
@Composable
private fun GroupRow(
    group: GroupRecord,
    onClick: () -> Unit,
) {
    val container = LocalAppContainer.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peopleLabel(group.participants, container.contactNames::nameFor).take(60),
                style = RadarType.serifTitle,
                color = RadarColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = group.body,
                style = RadarType.small,
                color = RadarColors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${group.participants.size} people",
                style = RadarType.micro,
                color = RadarColors.ink3,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${group.replies.size} replies",
                style = RadarType.microPlain,
                color = RadarColors.ink3,
            )
        }
    }
}

/**
 * One broadcast as a thread: the sent body, then everything tagged back to its
 * id. Messages are read from the group store — the same record the tag resolves
 * to — so a broadcast and its replies cannot disagree about who is in it.
 */
@Composable
fun GroupThreadScreen(
    groupId: String,
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val store = container.groupStore
    var record by remember(groupId) { mutableStateOf(store.get(groupId)) }
    var draft by remember(groupId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val participants = record?.participants.orEmpty()
    val replies = record?.replies.orEmpty()

    // Following the newest reply, like the 1:1 thread: the send is what you look
    // for, and a reply appended while open is the reason to be here.
    LaunchedEffect(replies.size) {
        if (replies.isNotEmpty()) listState.animateScrollToItem(replies.size)
    }

    fun send() {
        val body = draft.trim()
        if (body.isEmpty() || participants.isEmpty()) return
        val tagged = taggedBody(body, groupId)
        // Fan-out: one tagged send per participant, so each leg stands alone and
        // any of them can be replied to and resolved back to this id.
        scope.launch {
            participants.forEach { person -> runCatching { container.smsSender.send(person, tagged, null) } }
            store.attach(groupId, UUID.randomUUID().toString(), SELF, body, System.currentTimeMillis())
            draft = ""
            record = store.get(groupId)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = RadarColors.paper2) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(RadarColors.paper2),
        ) {
            RadarHeader(
                title = peopleLabel(participants, container.contactNames::nameFor).ifBlank { "Group" }.take(50),
                accent = RadarColors.ink,
                onBack = onBack,
                subtitle = {
                    Text(
                        text = "${participants.size} people ${'\u00B7'} ${replies.size} replies",
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                    )
                },
            )
            if (participants.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // A chip is the way into that person's own 1:1 thread — the
                    // group is a view over the same people, not a second inbox.
                    participants.take(4).forEach { person ->
                        Box(modifier = Modifier.clickable { onOpenContact(person) }) {
                            Chip(
                                text = container.contactNames.nameFor(person) ?: person,
                                color = RadarColors.ruleSoft,
                            )
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                item { GroupBubble(from = SELF, body = record?.body.orEmpty(), outgoing = true) }
                items(
                    items = replies,
                    key = { reply -> "${reply.from}|${reply.at}|${reply.body.hashCode()}" },
                ) { reply ->
                    GroupBubble(
                        from = reply.from,
                        body = reply.body,
                        outgoing = reply.from == SELF,
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            HardRule()
            GroupComposer(draft = draft, onDraft = { draft = it }, onSend = ::send)
        }
    }
}

/**
 * One message in a group thread. Incoming carries its sender, because who said
 * it is the whole reason a group reads differently from a 1:1.
 */
@Composable
private fun GroupBubble(
    from: String,
    body: String,
    outgoing: Boolean,
) {
    val container = LocalAppContainer.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        if (!outgoing) {
            Text(
                text = container.contactNames.nameFor(from) ?: from,
                style = RadarType.microBold,
                color = RadarColors.ink2,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        Box(
            modifier =
                Modifier
                    .background(
                        color = if (outgoing) RadarColors.ink else RadarColors.paper,
                        shape = RoundedCornerShape(8.dp),
                    ).then(
                        if (outgoing) {
                            Modifier
                        } else {
                            Modifier.border(1.dp, RadarColors.ruleSoft, RoundedCornerShape(8.dp))
                        },
                    ).padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = body,
                style = RadarType.small,
                color = if (outgoing) RadarColors.paper2 else RadarColors.ink,
            )
        }
    }
}

@Composable
private fun GroupComposer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            textStyle = RadarType.small.copy(color = RadarColors.ink),
            cursorBrush = SolidColor(RadarColors.ink),
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 120.dp)
                    .padding(vertical = 10.dp),
            decorationBox = { inner ->
                if (draft.isEmpty()) {
                    Text(
                        text = "Message the group",
                        style = RadarType.small,
                        color = RadarColors.ink3,
                    )
                }
                inner()
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "SEND",
            style = RadarType.micro,
            color = RadarColors.paper2,
            modifier =
                Modifier
                    .background(RadarColors.ink, RoundedCornerShape(6.dp))
                    .clickable(onClick = onSend)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private const val SELF = "me"

/** Names where known, numbers otherwise; a group is people, not addresses. */
internal fun peopleLabel(
    people: List<String>,
    nameFor: (String) -> String?,
): String = people.joinToString(", ") { nameFor(it) ?: it }
