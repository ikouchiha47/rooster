package com.personalos.app.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.personalos.app.core.sms.Contact
import com.personalos.app.core.sms.ContactDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device address book once per call into the send picker's [Contact]
 * list.
 *
 * Modelled on [AndroidContactNames] (the synchronous name lookup): it reads
 * `ContactsContract.CommonDataKinds.Phone`, is permission-guarded, and treats a
 * missing grant as an empty list rather than an error — the picker then offers
 * nothing to pick, which is the honest answer. The write surface is [all] alone;
 * callers own how many rows they show.
 */
class AndroidContactDirectory(
    private val context: Context,
) : ContactDirectory {
    override suspend fun all(): List<Contact> = withContext(Dispatchers.IO) { read() }

    private fun read(): List<Contact> {
        if (!granted()) return emptyList()
        return runCatching {
            // One row per phone number, deduped by the number so a contact with
            // the same line stored twice appears once. First name wins, so the
            // order cannot flap between reads.
            val byNumber = LinkedHashMap<String, Contact>()
            context.contentResolver
                .query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val number = normaliseNumber(cursor.getString(numberCol)) ?: continue
                        val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                        byNumber.putIfAbsent(number, Contact(name = name, number = number))
                    }
                }
            byNumber.values.sortedWith { a, b ->
                val byName = String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name)
                if (byName != 0) byName else a.number.compareTo(b.number)
            }
        }.getOrDefault(emptyList())
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
}

/**
 * A number as it should be dialled and compared: a single leading `+` (when the
 * stored form had one) and digits only, so `+91 98765 43210` and `+91-98765-43210`
 * are the same entry. Null when nothing usable remains.
 */
internal fun normaliseNumber(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val digits = trimmed.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    return if (trimmed.startsWith("+")) "+$digits" else digits
}
