package com.personalos.app.ui.messages

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.personalos.app.core.Chars
import com.personalos.app.core.sms.Contact
import com.personalos.app.ui.common.HardRule
import com.personalos.app.ui.common.HeaderMeta
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/**
 * The address book, as the recipient picker.
 *
 * Tap one contact to open compose with that recipient. Long-press to enter
 * selection; then a tap toggles, a "Message N" bar opens compose with all chosen,
 * and Cancel leaves selection. There is no second screen: the same list becomes
 * the picker, which is why selection is a *mode* on this list rather than a
 * destination of its own.
 *
 * Contacts are read through [com.personalos.app.core.sms.ContactDirectory] on the
 * container, so the screen owns no reader of its own (docs/CODE-DESIGN-GUIDELINES.md
 * §1). A missing `READ_CONTACTS` grant is an empty list, which this renders as a
 * clear line rather than a blank panel.
 */
@Composable
fun ContactsScreen(
    onPick: (String) -> Unit,
    onPickMany: (List<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var selection by remember { mutableStateOf(ContactsSelection.Idle) }

    val hasPermission =
        remember {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS,
            ) == PackageManager.PERMISSION_GRANTED
        }

    LaunchedEffect(Unit) {
        contacts = runCatching { container.contacts.all() }.getOrDefault(emptyList())
    }

    val ordered = remember(contacts) { orderedContacts(contacts) }

    Column(modifier = modifier.fillMaxSize().background(RadarColors.paper2)) {
        RadarHeader(
            title = "Contacts",
            accent = RadarColors.ink,
            onBack = onBack,
            trailing = {
                HeaderMeta(
                    if (selection.active) {
                        "${compactNumber(selection.count)} selected ${Chars.MIDDLE_DOT} ${compactNumber(ordered.size)} total"
                    } else {
                        "${compactNumber(ordered.size)} contacts"
                    },
                    Modifier.padding(end = 8.dp),
                )
            },
        )

        if (selection.active) {
            SelectionBar(
                count = selection.count,
                onMessage = {
                    if (selection.count > 0) onPickMany(selectedNumbers(ordered, selection.numbers))
                },
                onCancel = { selection = selection.cancel() },
            )
        }

        when {
            !hasPermission ->
                EmptyContacts(
                    title = "NO CONTACTS ACCESS",
                    line = "Grant contacts access in Android Settings to pick a recipient.",
                )

            ordered.isEmpty() ->
                EmptyContacts(
                    title = "NO CONTACTS",
                    line = "No contacts on this device.",
                )

            else ->
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items = ordered, key = { it.number }) { contact ->
                        val chosen = contact.number in selection.numbers
                        ContactRow(
                            contact = contact,
                            selecting = selection.active,
                            chosen = chosen,
                            onTap = {
                                if (selection.active) {
                                    selection = selection.toggle(contact.number)
                                } else {
                                    onPick(contact.number)
                                }
                            },
                            onLongPress = {
                                if (!selection.active) selection = selection.begin(contact.number)
                            },
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
                        .pointerInput(count) { detectTapGestures(onTap = { onMessage() }) }
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
                        .pointerInput(Unit) { detectTapGestures(onTap = { onCancel() }) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        HardRule()
    }
}

/** Name primary, number secondary; a checkbox appears only while selecting. */
@Composable
private fun ContactRow(
    contact: Contact,
    selecting: Boolean,
    chosen: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (chosen) RadarColors.fresh else RadarColors.paper2)
                .pointerInput(selecting, contact.number) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { onLongPress() },
                    )
                }.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectionBox(chosen)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = contact.name,
                style = RadarType.serifTitle,
                color = RadarColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = contact.number,
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                maxLines = 1,
            )
        }
    }
    HardRule()
}

/**
 * The selection checkbox in the app's own language: a 2dp square with a 1dp ink
 * border, filled ink with a check mark once chosen. Not a Material checkbox —
 * the paper bans its defaults, and no glyph in the set is a checkbox.
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
