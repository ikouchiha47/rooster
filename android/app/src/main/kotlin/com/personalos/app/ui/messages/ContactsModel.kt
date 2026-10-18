package com.personalos.app.ui.messages

import com.personalos.app.core.sms.CallInteraction
import com.personalos.app.core.sms.Contact
import com.personalos.app.data.SenderLast
import com.personalos.app.data.contacts.normaliseNumber
import com.personalos.app.ui.common.DayGroup
import com.personalos.app.ui.common.groupIntoDays

/**
 * The contacts picker's multi-select state, kept out of the composable so the
 * rule can be read (and tested) on its own — the same split as
 * [ConversationsModel] and [MessagesTab].
 *
 * There is no separate selection screen: [active] is what turns the same list
 * into a picker, and the chosen numbers are what the compose route carries. The
 * set spans both tabs, so switching Listed/Unlisted never drops a choice.
 */
data class ContactsSelection(
    val active: Boolean,
    val numbers: Set<String>,
) {
    val count: Int get() = numbers.size

    /** Long-press opens selection with the pressed contact already chosen. */
    fun begin(number: String): ContactsSelection = ContactsSelection(active = true, numbers = numbers + number)

    /** Tap toggles one contact while selection is active. */
    fun toggle(number: String): ContactsSelection = copy(numbers = if (number in numbers) numbers - number else numbers + number)

    /** Cancel leaves selection with nothing carried over. */
    fun cancel(): ContactsSelection = Idle

    companion object {
        val Idle = ContactsSelection(active = false, numbers = emptySet())
    }
}

/** One row's text: a name over a number, or a bare number flagged as unsaved. */
data class PickerEntry(
    val primary: String,
    val secondary: String,
    val number: String,
    val saved: Boolean,
)

/** A stretch of the picker: a letter heading, then its rows. */
sealed interface PickerItem {
    data class Header(
        val text: String,
    ) : PickerItem

    data class Entry(
        val entry: PickerEntry,
    ) : PickerItem
}

/** The action bar's label while selecting: "Message N", N being the count. */
fun messageCountLabel(count: Int): String = "Message $count"

/**
 * The one order saved contacts are shown in: name A to Z, then number so two
 * contacts with the same name do not flap. `AndroidContactDirectory` already
 * sorts, but the UI owns display order (docs/CODE-DESIGN-GUIDELINES.md §1), so it
 * is re-applied rather than trusted.
 */
fun orderedContacts(contacts: List<Contact>): List<Contact> = contacts.sortedWith(compareBy<Contact, String>(String.CASE_INSENSITIVE_ORDER, Contact::name).thenBy { it.number })

/**
 * The section a name files under: `#` for anything that does not begin with a
 * letter (digits, symbols, empty), otherwise the **uppercased** first letter —
 * so the grouping is case-insensitive (`abhi`, `Abhi` and `ABHI` all file under
 * `A`). ASCII-first on purpose; a full collator is a later refinement.
 */
fun bucketOf(name: String): String {
    val first = name.trim().firstOrNull()?.uppercaseChar()
    return if (first != null && first in 'A'..'Z') first.toString() else "#"
}

/** The **Listed** tab: saved contacts, grouped Symbols first then A..Z. */
fun listedItems(saved: List<Contact>): List<PickerItem> {
    val items = ArrayList<PickerItem>()
    saved
        .groupBy { bucketOf(it.name) }
        .toSortedMap(BUCKET_ORDER)
        .forEach { (bucket, contacts) ->
            items += PickerItem.Header(bucket)
            orderedContacts(contacts).forEach {
                items += PickerItem.Entry(PickerEntry(it.name, it.number, it.number, saved = true))
            }
        }
    return items
}

/** The **Unlisted** tab: SMS senders and attended-call numbers not in the book. */
fun unlistedItems(unsaved: List<String>): List<PickerItem> = unsaved.map { PickerItem.Entry(PickerEntry(primary = it, secondary = "Not in contacts", number = it, saved = false)) }

/**
 * First item index of each letter heading, so the side rail can jump straight to
 * a bucket. Keys are the same buckets [listedItems] emits, in order.
 */
fun letterIndex(items: List<PickerItem>): Map<String, Int> {
    val out = LinkedHashMap<String, Int>()
    items.forEachIndexed { index, item ->
        if (item is PickerItem.Header) out.putIfAbsent(item.text, index)
    }
    return out
}

/** Symbols first, then A..Z — the order the section headings read in. */
private val BUCKET_ORDER =
    Comparator<String> { a, b ->
        when {
            a == b -> 0
            a == "#" -> -1
            b == "#" -> 1
            else -> a.compareTo(b)
        }
    }

/**
 * The chosen numbers in the picker's own order, so the compose screen's chips
 * read in the order the reader tapped them through the sorted list — including
 * unsaved numbers, which are not in the address book at all.
 */
fun selectedNumbers(
    items: List<PickerItem>,
    numbers: Set<String>,
): List<String> =
    items
        .asSequence()
        .filterIsInstance<PickerItem.Entry>()
        .map { it.entry.number }
        .filter { it in numbers }
        .toList()

/** Which unsaved channel a tab shows. */
enum class UnlistedChannel { MESSAGED, ATTENDED, MISSED }

/** One unsaved row: the number and when it was last interacted with. */
data class UnlistedRow(
    val number: String,
    val lastAt: Long,
    val accountId: String? = null,
)

/**
 * The rows for one Unlisted channel, newest interaction first, saved contacts
 * removed and numbers deduped. Pure, so the merge rule is testable without the
 * providers or a clock.
 */
fun unlistedRows(
    senders: List<SenderLast>,
    calls: List<CallInteraction>,
    saved: Set<String>,
    channel: UnlistedChannel,
): List<UnlistedRow> =
    when (channel) {
        UnlistedChannel.MESSAGED ->
            senders.mapNotNull { sender ->
                normaliseNumber(sender.sender)?.takeIf { it !in saved }?.let { UnlistedRow(it, sender.lastAt, sender.sim) }
            }

        UnlistedChannel.ATTENDED ->
            calls.filter { it.attended && it.number !in saved }.map { UnlistedRow(it.number, it.lastAt, it.accountId) }

        UnlistedChannel.MISSED ->
            calls.filter { it.missed && it.number !in saved }.map { UnlistedRow(it.number, it.lastAt, it.accountId) }
    }.distinctBy { it.number }.sortedByDescending { it.lastAt }

/** The Unlisted rows grouped by day with the **Messages** grouper (sparse runs merge). */
fun dateGroups(rows: List<UnlistedRow>): List<DayGroup<UnlistedRow>> = groupIntoDays(rows) { it.lastAt }

/** The SIM label for a call's account id, by subscription id or ICC id. */
fun simLabelFor(
    accountId: String?,
    sims: List<com.personalos.app.core.sms.Sim>,
): String? =
    accountId?.let { id ->
        sims.firstOrNull { it.subscriptionId.toString() == id || (it.iccId != null && it.iccId == id) }?.label
    }

/** Live search over Unlisted rows by number. */
fun filterUnlisted(
    rows: List<UnlistedRow>,
    query: String,
): List<UnlistedRow> {
    val q = query.trim()
    return if (q.isEmpty()) rows else rows.filter { it.number.contains(q) }
}

/** Live search over saved contacts, name or number. */
fun filterSaved(
    contacts: List<Contact>,
    query: String,
): List<Contact> {
    val q = query.trim()
    if (q.isEmpty()) return contacts
    return contacts.filter { it.name.contains(q, ignoreCase = true) || it.number.contains(q) }
}
