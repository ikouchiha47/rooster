package com.personalos.app.ui.messages

import com.personalos.app.core.sms.Contact

/**
 * The contacts picker's multi-select state, kept out of the composable so the
 * rule can be read (and tested) on its own — the same split as
 * [ConversationsModel] and [MessagesTab].
 *
 * There is no separate selection screen: [active] is what turns the same list
 * into a picker, and the chosen numbers are what the compose route carries.
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

/** The action bar's label while selecting: "Message N", N being the count. */
fun messageCountLabel(count: Int): String = "Message $count"

/**
 * The one order the picker shows: name A to Z, then number so two contacts with
 * the same name do not flap. `AndroidContactDirectory` already sorts, but the UI
 * owns display order (docs/CODE-DESIGN-GUIDELINES.md §1), so it is re-applied
 * rather than trusted.
 */
fun orderedContacts(contacts: List<Contact>): List<Contact> =
    contacts.sortedWith(
        compareBy<Contact, String>(String.CASE_INSENSITIVE_ORDER, Contact::name).thenBy { it.number },
    )

/**
 * The chosen numbers in the picker's own order, so the compose screen's chips
 * read in the order the reader tapped them through the sorted list.
 */
fun selectedNumbers(
    contacts: List<Contact>,
    numbers: Set<String>,
): List<String> = orderedContacts(contacts).map { it.number }.filter { it in numbers }
