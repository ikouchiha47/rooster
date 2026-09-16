package com.personalos.app.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One SMS provider row, decoded. All provider knowledge (column indexes, cursor
 * lifetime) stays behind [SmsReader]; ingest only sees these values.
 */
data class SmsMessage(
    val id: Long,
    val address: String?,
    val body: String?,
    val date: Long,
    val type: Int,
)

/**
 * The provider read behind SMS ingest. [SmsSource] depends on this rather than
 * on `ContentResolver`, so the same ingest path can be exercised against a plain
 * list in a JVM test.
 */
interface SmsReader {
    /**
     * Rows with `_id > sinceId`, oldest first, or `null` when the provider can
     * not be read at all (no permission / no cursor). An empty list means the
     * provider was read and there was nothing new — a different fact from "no
     * access".
     */
    suspend fun messagesAfter(sinceId: Long): List<SmsMessage>?

    /**
     * Watches the provider for changes; [onChange] fires on the main thread.
     * Returns a handle that unregisters the observer when closed.
     */
    fun observe(onChange: () -> Unit): AutoCloseable
}

/** Reads `content://sms` through the platform resolver. */
class ContentResolverSmsReader(
    private val context: Context,
) : SmsReader {
    override suspend fun messagesAfter(sinceId: Long): List<SmsMessage>? =
        withContext(Dispatchers.IO) {
            val selection = if (sinceId > 0) "${Telephony.Sms._ID} > ?" else null
            val selectionArgs = if (sinceId > 0) arrayOf(sinceId.toString()) else null
            val projection =
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                )

            val cursor =
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${Telephony.Sms._ID} ASC",
                )

            cursor?.use { c ->
                val idIndex = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                val rows = ArrayList<SmsMessage>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) {
                    rows +=
                        SmsMessage(
                            id = c.getLong(idIndex),
                            address = c.getString(addressIndex),
                            body = c.getString(bodyIndex),
                            date = c.getLong(dateIndex),
                            type = c.getInt(typeIndex),
                        )
                }
                rows
            }
        }

    override fun observe(onChange: () -> Unit): AutoCloseable {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(
                    selfChange: Boolean,
                    uri: Uri?,
                ) {
                    onChange()
                }
            }
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        return AutoCloseable { context.contentResolver.unregisterContentObserver(observer) }
    }
}
