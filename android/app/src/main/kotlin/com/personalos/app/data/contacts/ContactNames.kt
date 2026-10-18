package com.personalos.app.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How many trailing digits identify a number (India: the national number). */
private const val MATCH_DIGITS = 10

/**
 * Fewer digits than this is not a phone number: a short code (`56767`) or a DLT
 * business header (`VM-HDFCBK-S`) has none or too few, and must never be matched
 * to a contact.
 */
private const val MIN_DIGITS = 7

/**
 * The key two numbers are compared by: the last [MATCH_DIGITS] digits, with every
 * non-digit stripped first — so `+91 98765 43210`, `09876543210` and `9876543210`
 * are the same sender. Null when the input carries no usable number, so a short
 * code or a business header can never resolve.
 */
fun senderMatchKey(sender: String?): String? {
    val digits = sender?.filter { it.isDigit() }.orEmpty()
    if (digits.length < MIN_DIGITS) return null
    return digits.takeLast(MATCH_DIGITS)
}

/**
 * The pure matcher: [byNumber] maps a contact's [senderMatchKey] to a display
 * name. Null means "not known" — an unknown number, a short code or a business
 * header — and the caller shows the raw sender. It never invents a name.
 */
fun contactNameFor(
    sender: String?,
    byNumber: Map<String, String>,
): String? = senderMatchKey(sender)?.let(byNumber::get)

/**
 * Resolves a stored SMS sender to the contact's display name.
 *
 * One owner of the contacts read: an implementation caches the address book and
 * answers synchronously, so a list row resolves with no query. An empty cache
 * (every lookup null) is the honest answer when READ_CONTACTS is not granted —
 * the app then shows the raw sender, exactly as before.
 */
interface ContactNames {
    /** The contact's display name for [sender], or null when unknown. */
    fun nameFor(sender: String?): String?
}

/**
 * Reads [ContactsContract.CommonDataKinds.Phone] once into a [senderMatchKey]-
 * keyed map. [refresh] is the only write and runs off the main thread; every read
 * is a permission-guarded, exception-safe snapshot.
 */
class AndroidContactNames(
    private val context: Context,
) : ContactNames {
    @Volatile
    private var byNumber: Map<String, String> = emptyMap()

    override fun nameFor(sender: String?): String? = contactNameFor(sender, byNumber)

    /**
     * Re-reads the address book into the cache. Safe to call repeatedly, and safe
     * to call without the permission: it then caches nothing and clears any prior
     * map, so a revoked grant cannot keep resolving names.
     */
    suspend fun refresh() {
        byNumber = withContext(Dispatchers.IO) { read() }
    }

    private fun read(): Map<String, String> {
        if (!granted()) return emptyMap()
        return runCatching {
            val out = HashMap<String, String>()
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
                        val key = senderMatchKey(cursor.getString(numberCol)) ?: continue
                        val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                        // A number can be on several rows; the first name wins,
                        // so a refresh cannot flap between them.
                        out.putIfAbsent(key, name)
                    }
                }
            out
        }.getOrDefault(emptyMap())
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
}
