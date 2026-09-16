package com.personalos.app.data

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.personalos.app.core.SmsClassifier
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.Transport
import com.personalos.app.core.tag.Ulid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsSource(
    private val context: Context,
    private val database: AppDatabase,
    private val tagWriter: TagWriter,
    private val mentionWriter: MentionWriter,
) : LifecycleObserver {
    private companion object {
        const val TAG = "M1"
        const val PREFS = "sms_source"
        const val KEY_LAST_SEEN_ID = "last_seen_id"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var contentObserver: SmsContentObserver? = null
    private var isObserving = false

    /**
     * Highest SMS _id already ingested, persisted across process starts so a
     * cold launch does not re-scan and re-insert the whole inbox.
     */
    @Volatile
    private var lastSeenId: Long = prefs.getLong(KEY_LAST_SEEN_ID, 0L)

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun startObserving() {
        if (isObserving) return
        val observer = SmsContentObserver(Handler(Looper.getMainLooper()))
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            observer,
        )
        contentObserver = observer
        isObserving = true
        Log.i(TAG, "SmsSource: observing content://sms (lastSeenId=$lastSeenId)")
        ingestNewSms()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun stopObserving() {
        if (!isObserving) return
        contentObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        contentObserver = null
        isObserving = false
        Log.i(TAG, "SmsSource: stopped observing content://sms")
    }

    private fun ingestNewSms() {
        scope.launch {
            try {
                ingestSms()
            } catch (e: SecurityException) {
                Log.e(TAG, "SmsSource: SecurityException reading SMS - permission not granted", e)
            } catch (e: Exception) {
                Log.e(TAG, "SmsSource: error ingesting SMS", e)
            }
        }
    }

    private suspend fun ingestSms() =
        withContext(Dispatchers.IO) {
            resetMarkIfTableIsEmpty()
            tagWriter.ensureActive()

            val since = lastSeenId
            val selection = if (since > 0) "${Telephony.Sms._ID} > ?" else null
            val selectionArgs = if (since > 0) arrayOf(since.toString()) else null
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

                val events = ArrayList<EventEntity>(c.count.coerceAtLeast(0))
                val tagInputs = ArrayList<TagInput>(c.count.coerceAtLeast(0))
                var maxId = since
                val ingestedAt = System.currentTimeMillis()

                while (c.moveToNext()) {
                    val rowId = c.getLong(idIndex)
                    if (rowId > maxId) maxId = rowId

                    val address = c.getString(addressIndex) ?: "Unknown"
                    val body = c.getString(bodyIndex) ?: ""

                    // Classify once, at ingest, so the category is queryable
                    // (Radar filters on it) instead of recomputed per render.
                    val category = SmsClassifier.classify(address, body).klass.name

                    events.add(
                        EventEntity(
                            ulid = Ulid.next(),
                            // Provider row id: re-ingest is idempotent, and two
                            // messages from the same sender never collide.
                            dedupeKey = "sms:$rowId",
                            source = "sms",
                            type = typeName(c.getInt(typeIndex)),
                            category = category,
                            timestamp = c.getLong(dateIndex),
                            title = address,
                            content = body,
                            entities = "[]",
                            location = null,
                            url = null,
                            ingestedAt = ingestedAt,
                        ),
                    )
                    tagInputs.add(TagInput(text = body, source = Transport.SMS, sender = address))
                }

                if (events.isEmpty()) {
                    Log.i(TAG, "SmsSource: no new SMS (lastSeenId=$since)")
                    return@use
                }

                val inserted = database.eventDao().insertAll(events)

                // Tag only what actually landed: duplicates come back as -1.
                val toTag = ArrayList<Pair<String, TagInput>>(events.size)
                var newCount = 0
                inserted.forEachIndexed { index, insertedId ->
                    if (insertedId == -1L) return@forEachIndexed
                    newCount++
                    toTag += events[index].ulid to tagInputs[index]
                }
                if (toTag.isNotEmpty()) tagWriter.writeAll(toTag)
                if (toTag.isNotEmpty()) {
                    mentionWriter.writeAll(toTag.map { (ulid, input) -> ulid to input.text })
                }

                lastSeenId = maxId
                prefs.edit().putLong(KEY_LAST_SEEN_ID, maxId).apply()
                Log.i(TAG, "SmsSource: ingested $newCount new SMS (scanned ${events.size}, lastSeenId=$maxId)")
            } ?: Log.i(TAG, "SmsSource: cursor is null - no SMS access")
        }

    /**
     * A destructive migration (schema bump) recreates the table empty. The stored
     * high-water mark would then suppress re-ingestion entirely, so clear it when
     * the events table is empty.
     */
    private suspend fun resetMarkIfTableIsEmpty() {
        if (lastSeenId <= 0) return
        if (database.eventDao().getCount() > 0) return
        lastSeenId = 0
        prefs.edit().putLong(KEY_LAST_SEEN_ID, 0L).apply()
        Log.i(TAG, "SmsSource: table empty but mark was set - re-ingesting from scratch")
    }

    private fun typeName(type: Int): String =
        when (type) {
            Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
            Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
            Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
            Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
            else -> "unknown"
        }

    private inner class SmsContentObserver(
        handler: Handler,
    ) : ContentObserver(handler) {
        override fun onChange(
            selfChange: Boolean,
            uri: Uri?,
        ) {
            ingestNewSms()
        }
    }
}
