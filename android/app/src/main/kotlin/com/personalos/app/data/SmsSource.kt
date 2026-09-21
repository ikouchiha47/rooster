package com.personalos.app.data

import android.provider.Telephony
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.personalos.app.core.SmsAmountParser
import com.personalos.app.core.SmsClassifier
import com.personalos.app.core.rules.FieldNames
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.tag.TagInput
import com.personalos.app.core.tag.Transport
import com.personalos.app.core.tag.Ulid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one SMS ingest entry point. The foreground observer in [SmsSource] and
 * `SmsSyncWorker` in the background both call [ingestNewMessages]; [SmsSource]
 * is the only implementation, so the two paths cannot diverge.
 */
interface SmsIngest {
    /**
     * Reads messages newer than the persisted high-water mark and stores what is
     * new. Returns the number of rows that actually landed.
     */
    suspend fun ingestNewMessages(): Int
}

/**
 * Reads the device's SMS inbox into the store.
 *
 * Two callers, one code path:
 *  - [startObserving] registers a content observer on `ON_START` and unregisters
 *    on `ON_STOP`, giving immediacy while the app is foregrounded;
 *  - `SmsSyncWorker` calls [ingestNewMessages] on a periodic background schedule,
 *    covering the time the app spends closed.
 *
 * [reader] and [mark] isolate the two platform touchpoints (the ContentResolver
 * and SharedPreferences) so the ingest itself is a plain suspend function.
 */
class SmsSource(
    private val reader: SmsReader,
    private val mark: SmsSyncMark,
    private val eventDao: EventDao,
    private val tagWriter: TagWriter,
    private val mentionWriter: MentionWriter,
    /**
     * Typed extras for the one kind that can supply them today (ADR 0003 §3,
     * §13): `sender` from the address it already has, `amount` from the body.
     */
    private val fieldWriter: FieldWriter,
    /**
     * Evaluates enabled rules against items that actually landed, after their
     * tags, mentions and fields are stored — so a `source = sms` + `amount`
     * rule can match (ADR §10, R2).
     */
    private val ruleWriter: RuleWriter,
    /** Every ingest writes one run row, so the Events tab sees SMS like any source. */
    private val syncRecorder: SyncRecorder = NoOpSyncRecorder,
) : LifecycleObserver,
    SmsIngest {
    companion object {
        /** `events.source` for every SMS row — the identity a `source` predicate reads. */
        const val SOURCE_ID = "sms"

        private const val TAG = "M1"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private var observation: AutoCloseable? = null
    private var isObserving = false

    /**
     * Highest SMS _id already ingested, persisted across process starts so a
     * cold launch does not re-scan and re-insert the whole inbox.
     */
    @Volatile
    private var lastSeenId: Long = mark.read()

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun startObserving() {
        if (isObserving) return
        observation = reader.observe { ingestNewSms() }
        isObserving = true
        Log.i(TAG, "SmsSource: observing content://sms (lastSeenId=$lastSeenId)")
        ingestNewSms()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun stopObserving() {
        if (!isObserving) return
        observation?.close()
        observation = null
        isObserving = false
        Log.i(TAG, "SmsSource: stopped observing content://sms")
    }

    private fun ingestNewSms() {
        scope.launch {
            try {
                ingestNewMessages()
            } catch (e: SecurityException) {
                Log.e(TAG, "SmsSource: SecurityException reading SMS - permission not granted", e)
            } catch (e: Exception) {
                Log.e(TAG, "SmsSource: error ingesting SMS", e)
            }
        }
    }

    override suspend fun ingestNewMessages(): Int =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            resetMarkIfTableIsEmpty()
            tagWriter.ensureActive()

            val since = lastSeenId
            val messages = reader.messagesAfter(since)
            if (messages == null) {
                Log.i(TAG, "SmsSource: no SMS access")
                syncRecorder.record(SyncRun(SOURCE_ID, "sms", started, System.currentTimeMillis(), false, 0, "no SMS access"))
                return@withContext 0
            }
            if (messages.isEmpty()) {
                Log.i(TAG, "SmsSource: no new SMS (lastSeenId=$since)")
                syncRecorder.record(SyncRun(SOURCE_ID, "sms", started, System.currentTimeMillis(), true, 0, null))
                return@withContext 0
            }

            val ingestedAt = System.currentTimeMillis()
            val events = ArrayList<EventEntity>(messages.size)
            val tagInputs = ArrayList<TagInput>(messages.size)
            val fieldInputs = ArrayList<Map<String, FieldValue>>(messages.size)
            var maxId = since

            for (message in messages) {
                if (message.id > maxId) maxId = message.id

                val address = message.address ?: "Unknown"
                val body = message.body ?: ""

                // Classify once, at ingest, so the category is queryable
                // (Radar filters on it) instead of recomputed per render.
                val category = SmsClassifier.classify(address, body).klass.name

                events.add(
                    EventEntity(
                        ulid = Ulid.next(),
                        // Provider row id: re-ingest is idempotent, and two
                        // messages from the same sender never collide.
                        dedupeKey = "sms:${message.id}",
                        source = SOURCE_ID,
                        type = typeName(message.type),
                        category = category,
                        timestamp = message.date,
                        title = address,
                        content = body,
                        entities = "[]",
                        location = null,
                        url = null,
                        ingestedAt = ingestedAt,
                    ),
                )
                tagInputs.add(TagInput(text = body, source = Transport.SMS, sender = address))
                fieldInputs.add(smsFields(address, body))
            }

            val inserted = eventDao.insertAll(events)

            // Tag only what actually landed: duplicates come back as -1.
            val toTag = ArrayList<Pair<String, TagInput>>(events.size)
            val toFields = ArrayList<Pair<String, Map<String, FieldValue>>>(events.size)
            val landed = ArrayList<EventEntity>(events.size)
            var newCount = 0
            inserted.forEachIndexed { index, insertedId ->
                if (insertedId == -1L) return@forEachIndexed
                newCount++
                toTag += events[index].ulid to tagInputs[index]
                toFields += events[index].ulid to fieldInputs[index]
                landed += events[index]
            }
            if (toTag.isNotEmpty()) tagWriter.writeAll(toTag)
            if (toTag.isNotEmpty()) {
                mentionWriter.writeAll(toTag.map { (ulid, input) -> ulid to input.text })
            }
            // Fields before evaluation: the writer reads them from the store
            // (ADR §13), so they must be persisted first.
            if (toFields.isNotEmpty()) fieldWriter.writeAll(toFields)
            if (landed.isNotEmpty()) ruleWriter.writeAll(landed.map { it.toRuleItemSeed() })

            lastSeenId = maxId
            mark.write(maxId)
            Log.i(TAG, "SmsSource: ingested $newCount new SMS (scanned ${events.size}, lastSeenId=$maxId)")
            syncRecorder.record(SyncRun(SOURCE_ID, "sms", started, System.currentTimeMillis(), true, newCount, null))
            newCount
        }

    /**
     * A destructive migration (schema bump) recreates the table empty. The stored
     * high-water mark would then suppress re-ingestion entirely, so clear it when
     * the events table is empty.
     */
    private suspend fun resetMarkIfTableIsEmpty() {
        if (lastSeenId <= 0) return
        if (eventDao.getCount() > 0) return
        lastSeenId = 0
        mark.write(0L)
        Log.i(TAG, "SmsSource: table empty but mark was set - re-ingesting from scratch")
    }

    /**
     * The typed extras a message can supply: its `sender` (the address it
     * already has, never parsed) and, when the parser can attribute one safely,
     * its transaction `amount` (ADR §13). An absent `amount` is absent — the
     * evaluator reads a missing field as "not stored", never as zero.
     */
    private fun smsFields(
        address: String,
        body: String,
    ): Map<String, FieldValue> {
        val fields = mutableMapOf<String, FieldValue>(FieldNames.SENDER to FieldValue.Str(address))
        SmsAmountParser.parse(body)?.let { fields[FieldNames.AMOUNT] = FieldValue.Num(it) }
        return fields
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
}
