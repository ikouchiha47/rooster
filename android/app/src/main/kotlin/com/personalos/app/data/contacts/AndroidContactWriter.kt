package com.personalos.app.data.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.personalos.app.core.sms.ContactWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates a **local** contact (no account) via `ContactsContract` — a raw contact
 * plus a name and a phone row, in one batch. Permission-guarded; a missing
 * `WRITE_CONTACTS` (or a blank field) is `false`, never a throw.
 */
class AndroidContactWriter(
    private val context: Context,
) : ContactWriter {
    override suspend fun create(
        name: String,
        number: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (name.isBlank() || number.isBlank()) return@withContext false
            if (!granted()) return@withContext false
            runCatching {
                val rawIndex = 0
                val ops = ArrayList<ContentProviderOperation>()
                ops +=
                    ContentProviderOperation
                        .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                ops +=
                    ContentProviderOperation
                        .newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                        ).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build()
                ops +=
                    ContentProviderOperation
                        .newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                        ).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .build()
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                true
            }.getOrDefault(false)
        }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
}
