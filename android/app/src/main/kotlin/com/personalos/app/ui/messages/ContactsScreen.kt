package com.personalos.app.ui.messages

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.personalos.app.core.Chars
import com.personalos.app.core.sms.CallInteraction
import com.personalos.app.core.sms.Contact
import com.personalos.app.core.time.UserZone
import com.personalos.app.data.SenderLast
import com.personalos.app.ui.common.DayGroup
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.HeaderMeta
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SearchField
import com.personalos.app.ui.common.TabSpec
import com.personalos.app.ui.common.TabStrip
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.common.dayLabel
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch

/**
 * The address book as the recipient picker.
 *
 * - **Listed** — address book, A..Z with the rotary **drum** index. Hold the
 *   drum to type its letter into the search; drag it to jump.
 * - **Messaged / Attended / Missed** — unsaved numbers by channel, grouped by the
 *   same day grouper the Messages timeline uses, each row showing a channel dot
 *   and the date **and** time of the last interaction.
 *
 * Search filters the current tab; the drum types into it without focus, so the
 * keyboard only opens when the field itself is tapped.
 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    onPick: (String) -> Unit,
    onPickMany: (List<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    var saved by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var senders by remember { mutableStateOf<List<SenderLast>>(emptyList()) }
    var calls by remember { mutableStateOf<List<CallInteraction>>(emptyList()) }
    var selection by remember { mutableStateOf(ContactsSelection.Idle) }
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var simFilter by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newNumber by remember { mutableStateOf("") }
    var saveToGoogle by remember { mutableStateOf(false) }
    var googleOnly by remember { mutableStateOf<Boolean?>(null) }
    var sims by remember { mutableStateOf<List<com.personalos.app.core.sms.Sim>>(emptyList()) }
    val wheelRotation = remember { mutableFloatStateOf(0f) }
    val listedState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val hasContactsPermission =
        remember {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS,
            ) == PackageManager.PERMISSION_GRANTED
        }

    LaunchedEffect(Unit) {
        saved = runCatching { container.contacts.all() }.getOrDefault(emptyList())
        senders = runCatching { container.conversations.sendersWithLast() }.getOrDefault(emptyList())
        calls = runCatching { container.recentCallers.interactions() }.getOrDefault(emptyList())
        sims = runCatching { container.sims.active() }.getOrDefault(emptyList())
    }

    val savedNumbers = remember(saved) { saved.mapTo(HashSet()) { it.number } }
    val now = remember(senders, calls) { System.currentTimeMillis() }

    val filteredSaved =
        remember(saved, query, googleOnly) {
            filterSaved(saved, query).filter { googleOnly == null || it.google == googleOnly }
        }
    val listed = remember(filteredSaved) { listedItems(filteredSaved) }
    // The drum's letters stay the full A..Z set while typing, or filtering to one
    // letter would immediately leave nothing to rotate to.
    val wheelLetters = remember(saved) { letterIndex(listedItems(saved)) }
    val wheelOrder = remember(wheelLetters) { wheelLetters.keys.toList() }
    val wheelStep = remember(wheelOrder) { if (wheelOrder.isEmpty()) 0f else 360f / wheelOrder.size }

    val allMessaged = remember(senders, calls, savedNumbers) { unlistedRows(senders, calls, savedNumbers, UnlistedChannel.MESSAGED) }
    val allAttended = remember(senders, calls, savedNumbers) { unlistedRows(senders, calls, savedNumbers, UnlistedChannel.ATTENDED) }
    val allMissed = remember(senders, calls, savedNumbers) { unlistedRows(senders, calls, savedNumbers, UnlistedChannel.MISSED) }
    val messaged =
        remember(allMessaged, query, simFilter, sims) {
            filterUnlisted(allMessaged, query).filter { simFilter == null || simLabelFor(it.accountId, sims) == simFilter }
        }
    val attended =
        remember(allAttended, query, simFilter, sims) {
            filterUnlisted(allAttended, query).filter { simFilter == null || simLabelFor(it.accountId, sims) == simFilter }
        }
    val missed =
        remember(allMissed, query, simFilter, sims) {
            filterUnlisted(allMissed, query).filter { simFilter == null || simLabelFor(it.accountId, sims) == simFilter }
        }
    val messagedGroups = remember(messaged) { dateGroups(messaged) }
    val attendedGroups = remember(attended) { dateGroups(attended) }
    val missedGroups = remember(missed) { dateGroups(missed) }
    val unsavedTotal =
        remember(allMessaged, allAttended, allMissed) {
            (allMessaged + allAttended + allMissed).distinctBy { it.number }.size
        }
    val allPickerItems =
        remember(listed, messaged, attended, missed) {
            listed +
                (messaged + attended + missed)
                    .distinctBy { it.number }
                    .map { PickerItem.Entry(PickerEntry(it.number, "", it.number, saved = false)) }
        }

    // The list drives the drum: the top passing section sets the front letter.
    LaunchedEffect(listedState.firstVisibleItemIndex, query) {
        if (query.isNotBlank() || wheelOrder.isEmpty()) return@LaunchedEffect
        var letter = wheelOrder.first()
        for (i in 0..minOf(listedState.firstVisibleItemIndex, listed.lastIndex)) {
            val item = listed[i]
            if (item is PickerItem.Header && item.text in wheelLetters) letter = item.text
        }
        wheelRotation.floatValue = wheelOrder.indexOf(letter).coerceAtLeast(0) * wheelStep
    }

    val onTap: (String) -> Unit = { number ->
        if (selection.active) selection = selection.toggle(number) else onPick(number)
    }
    val onLongPress: (String) -> Unit = { number ->
        if (!selection.active) selection = selection.begin(number)
    }

    Column(modifier = modifier.fillMaxSize().background(RadarColors.paper2)) {
        RadarHeader(
            title = "Contacts",
            accent = RadarColors.ink,
            onBack = onBack,
            trailing = {
                HeaderMeta(
                    if (selection.active) {
                        "${compactNumber(selection.count)} selected"
                    } else {
                        "${compactNumber(saved.size)} listed ${Chars.MIDDLE_DOT} ${compactNumber(unsavedTotal)} unsaved"
                    },
                    Modifier.padding(end = 8.dp),
                )
            },
        )

        TabStrip(
            items =
                listOf(
                    TabSpec("Listed", saved.size),
                    TabSpec("Messaged", allMessaged.size),
                    TabSpec("Attended", allAttended.size),
                    TabSpec("Missed", allMissed.size),
                ),
            selectedIndex = tab,
            onSelect = { tab = it },
        )

        if (creating) {
            NewContactForm(
                name = newName,
                number = newNumber,
                onName = { newName = it },
                onNumber = { newNumber = it },
                onCancel = {
                    creating = false
                    newName = ""
                    newNumber = ""
                },
                google = saveToGoogle,
                onGoogle = { saveToGoogle = it },
                onSave = {
                    scope.launch {
                        val ok =
                            runCatching { container.contactWriter.create(newName.trim(), newNumber.trim(), saveToGoogle) }
                                .getOrDefault(false)
                        if (ok) {
                            saved = runCatching { container.contacts.all() }.getOrDefault(saved)
                            creating = false
                            newName = ""
                            newNumber = ""
                            saveToGoogle = false
                        }
                    }
                },
            )
        }

        SearchField(
            hint = if (tab == 0) "Search contacts" else "Search numbers",
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )

        if (tab == 0) {
            AccountFilterRow(selected = googleOnly, onSelect = { googleOnly = it })
        }

        if (sims.size > 1 && tab >= 1) {
            SimFilterRow(
                labels = sims.map { it.label },
                selected = simFilter,
                onSelect = { simFilter = it },
            )
        }

        if (selection.active) {
            SelectionBar(
                count = selection.count,
                onMessage = {
                    if (selection.count > 0) onPickMany(selectedNumbers(allPickerItems, selection.numbers))
                },
                onCancel = { selection = selection.cancel() },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> {
                    ListedList(
                        items = listed,
                        state = listedState,
                        selecting = selection.active,
                        numbers = selection.numbers,
                        empty =
                            if (!hasContactsPermission) {
                                "NO CONTACTS ACCESS" to "Grant contacts access in Android Settings to pick a recipient."
                            } else {
                                "NO CONTACTS" to "No contacts match this search."
                            },
                        onTap = onTap,
                        onLongPress = onLongPress,
                    )
                    if (wheelOrder.isNotEmpty()) {
                        WheelIndex(
                            letters = wheelOrder,
                            rotation = wheelRotation,
                            onJump = { letter ->
                                val target = listed.indexOfFirst { it is PickerItem.Header && it.text == letter }
                                if (target >= 0) scope.launch { listedState.scrollToItem(target) }
                            },
                            onHold = { letter -> query += letter },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                        )
                    }
                }

                1 ->
                    DateList(
                        groups = messagedGroups,
                        now = now,
                        sims = sims,
                        dot = CategoryColors.Teal,
                        selecting = selection.active,
                        numbers = selection.numbers,
                        empty = "NO UNSAVED MESSAGES" to "No messages from numbers that are not saved contacts.",
                        onTap = onTap,
                        onLongPress = onLongPress,
                    )

                2 ->
                    DateList(
                        groups = attendedGroups,
                        now = now,
                        sims = sims,
                        dot = CategoryColors.Chartreuse,
                        selecting = selection.active,
                        numbers = selection.numbers,
                        empty = "NO UNSAVED CALLS" to "No connected calls with numbers that are not saved contacts.",
                        onTap = onTap,
                        onLongPress = onLongPress,
                    )

                else ->
                    DateList(
                        groups = missedGroups,
                        now = now,
                        sims = sims,
                        dot = CategoryColors.Vermilion,
                        selecting = selection.active,
                        numbers = selection.numbers,
                        empty = "NO MISSED CALLS" to "No missed calls from numbers that are not saved contacts.",
                        onTap = onTap,
                        onLongPress = onLongPress,
                    )
            }

            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(48.dp)
                        .background(RadarColors.ink, RoundedCornerShape(12.dp))
                        .clickable { creating = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+", style = RadarType.serifH3, color = RadarColors.paper2)
            }
        }
    }
}

/** The Listed tab: letter headings (sticky) and rows, or a clear empty line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListedList(
    items: List<PickerItem>,
    state: LazyListState,
    selecting: Boolean,
    numbers: Set<String>,
    empty: Pair<String, String>,
    onTap: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyContacts(title = empty.first, line = empty.second)
        return
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items.forEach { row ->
            when (row) {
                is PickerItem.Header -> stickyHeader(key = "h:${row.text}") { SectionHeading(row.text) }
                is PickerItem.Entry -> {
                    val entry = row.entry
                    item(key = "r:${entry.number}") {
                        ContactRow(
                            entry = entry,
                            dot = null,
                            selecting = selecting,
                            chosen = entry.number in numbers,
                            onTap = { onTap(entry.number) },
                            onLongPress = { onLongPress(entry.number) },
                        )
                    }
                }
            }
        }
    }
}

/** One Unlisted tab: date groups (shared grouper), channel dot, date + time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DateList(
    groups: List<DayGroup<UnlistedRow>>,
    now: Long,
    sims: List<com.personalos.app.core.sms.Sim>,
    dot: Color,
    selecting: Boolean,
    numbers: Set<String>,
    empty: Pair<String, String>,
    onTap: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyContacts(title = empty.first, line = empty.second)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { group ->
            stickyHeader(key = "h:${group.dayStart}") {
                SectionHeading(dayLabel(group.dayStart))
            }
            group.items.forEach { row ->
                item(key = "r:${row.number}") {
                    val simLabel = simLabelFor(row.accountId, sims)
                    val simColor = SIM_BADGE_COLORS.getOrElse(sims.indexOfFirst { it.label == simLabel }) { SIM_BADGE_COLORS.first() }
                    ContactRow(
                        entry =
                            PickerEntry(
                                row.number,
                                interactionStamp(now, row.lastAt, UserZone.current),
                                row.number,
                                saved = false,
                            ),
                        dot = dot,
                        sim = simLabel,
                        simColor = simColor,
                        selecting = selecting,
                        chosen = row.number in numbers,
                        onTap = { onTap(row.number) },
                        onLongPress = { onLongPress(row.number) },
                    )
                }
            }
        }
    }
}

/**
 * The selection action bar: the proceed control reads the count ("Message N"),
 * Cancel leaves selection. On ink, like every other band that means "mode".
 */
@Composable
private fun SelectionBar(
    count: Int,
    onMessage: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HardRule()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RadarColors.ink),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable { onMessage() }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    text = messageCountLabel(count).uppercase(),
                    style = RadarType.label,
                    color = if (count > 0) RadarColors.paper2 else RadarColors.ink3,
                )
            }
            Text(
                text = "CANCEL",
                style = RadarType.micro,
                color = RadarColors.paper4,
                modifier =
                    Modifier
                        .clickable { onCancel() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        HardRule()
    }
}

// Light badge tints per SIM slot, so the two SIMs read apart at a glance.
private val SIM_BADGE_COLORS = listOf(CategoryColors.Indigo, CategoryColors.Rust)

@Composable
private fun AccountFilterRow(
    selected: Boolean?,
    onSelect: (Boolean?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        listOf(null to "ALL", true to "GOOGLE", false to "LOCAL").forEach { (value, label) ->
            val on = selected == value
            Text(
                text = label,
                style = RadarType.micro,
                color = if (on) RadarColors.paper2 else RadarColors.ink2,
                modifier =
                    Modifier
                        .clickable { onSelect(value) }
                        .background(if (on) RadarColors.ink else RadarColors.paper)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}

/** The inline "new contact" form: name + number, saved to the device address book. */
@Composable
private fun NewContactForm(
    name: String,
    number: String,
    google: Boolean,
    onName: (String) -> Unit,
    onNumber: (String) -> Unit,
    onGoogle: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val ready = name.isNotBlank() && number.isNotBlank()
    Column(Modifier.fillMaxWidth().background(RadarColors.paper).padding(8.dp)) {
        Text(text = "NEW CONTACT", style = RadarType.micro, color = RadarColors.ink3)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(true to "GOOGLE", false to "LOCAL").forEach { (value, label) ->
                val on = google == value
                Text(
                    text = label,
                    style = RadarType.micro,
                    color = if (on) RadarColors.paper2 else RadarColors.ink2,
                    modifier =
                        Modifier
                            .clickable { onGoogle(value) }
                            .background(if (on) RadarColors.ink else RadarColors.paper)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        SearchField(hint = "Name", query = name, onQueryChange = onName, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        SearchField(hint = "Phone number", query = number, onQueryChange = onNumber, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "SAVE",
                style = RadarType.label,
                color = if (ready) RadarColors.ink else RadarColors.ink3,
                modifier = Modifier.clickable { if (ready) onSave() }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "CANCEL",
                style = RadarType.micro,
                color = RadarColors.ink3,
                modifier = Modifier.clickable { onCancel() }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** The SIM names as filters (All + one per SIM); only shown for call tabs. */
@Composable
private fun SimFilterRow(
    labels: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
        (listOf(null) + labels).forEach { label ->
            val on = selected == label
            Text(
                text = label?.uppercase() ?: "ALL",
                style = RadarType.micro,
                color = if (on) RadarColors.paper2 else RadarColors.ink2,
                modifier =
                    Modifier
                        .clickable { onSelect(label) }
                        .background(if (on) RadarColors.ink else RadarColors.paper)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}

/** A letter or date heading; sticks to the top while its rows scroll under it. */
@Composable
private fun SectionHeading(text: String) {
    Column(Modifier.fillMaxWidth().background(RadarColors.paper4)) {
        Text(
            text = text.uppercase(),
            style = RadarType.micro,
            color = RadarColors.ink2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Primary over secondary; an optional channel dot; a short inset hairline, not a full rule. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    entry: PickerEntry,
    dot: Color?,
    sim: String? = null,
    simColor: Color = RadarColors.ink,
    selecting: Boolean,
    chosen: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(if (chosen) RadarColors.fresh else RadarColors.paper2)
                    .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                SelectionBox(chosen)
                Spacer(Modifier.width(8.dp))
            }
            if (dot != null) {
                Box(Modifier.size(7.dp).background(dot, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.primary,
                        style = RadarType.serifTitle,
                        color = RadarColors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (sim != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = sim.uppercase(),
                            style = RadarType.microPlain,
                            color = simColor,
                            modifier =
                                Modifier
                                    .background(simColor.copy(alpha = 0.14f), RoundedCornerShape(2.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.size(2.dp))
                Text(
                    text = entry.secondary,
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = if (dot != null) 23.dp else 8.dp)
                .height(1.dp)
                .background(RadarColors.ruleSoft),
        )
    }
}

/**
 * The selection checkbox in the app's own language: a 2dp square with a 1dp ink
 * border, filled ink with a check mark once chosen. Not a Material checkbox.
 */
@Composable
private fun SelectionBox(chosen: Boolean) {
    Box(
        modifier =
            Modifier
                .size(16.dp)
                .background(if (chosen) RadarColors.ink else Color.Transparent, RoundedCornerShape(2.dp))
                .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) {
            Text(
                text = Chars.CHECK,
                style = RadarType.microPlain,
                color = RadarColors.paper2,
            )
        }
    }
}

@Composable
private fun EmptyContacts(
    title: String,
    line: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(text = title, style = RadarType.micro, color = CategoryColors.Vermilion)
        Spacer(Modifier.size(6.dp))
        Text(text = line, style = RadarType.small, color = RadarColors.ink3)
    }
}
