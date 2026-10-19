package com.personalos.app.data

import android.provider.Telephony
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.personalos.app.core.PhoneNumbers
import com.personalos.app.core.SmsAmountParser
import com.personalos.app.core.category.Categorizer
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

    /**
     * Stores a message this app just sent, so the thread shows our own outgoing
     * side. **Not** the only writer: on some devices the platform also lands an
     * outgoing row in `content://sms`, which the ingest then reads — so both paths
     * must compute the *same* [sentDedupeKey], or a send becomes two rows.
     */
    suspend fun enqueueSent(
        destination: String,
        body: String,
        subscriptionId: Int?,
        timestampMs: Long,
    ): String

    /** The send's outcome, applied to the row [enqueueSent] wrote. */
    suspend fun markResult(
        dedupeKey: String,
        sent: Boolean,
    )
}

/**
 * The one identity for a message **we sent**, computed identically by our own
 * record and by the provider ingest (which also records outgoing rows on some
 * devices). Whichever writes first wins; the other collides in
 * `unique(dedupe_key)` and is dropped, so a send is one row. The minute bucket
 * keeps two genuinely identical messages sent at different times apart.
 */
internal fun sentDedupeKey(
    destination: String,
    body: String,
    at: Long,
): String {
    val who = destination.trim()
    val content = "$who\u0000${body.trim()}".hashCode()
    return "sms:sent:$who:$content:${at / 60_000}"
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
    /**
     * The one categorizer (the taxonomy is seeded from the DB before first use,
     * so this is resolved lazily rather than at construction).
     */
    private val categorizer: suspend () -> Categorizer,
    /** Whether a sender is a saved contact: the contact-wins rule's input. */
    private val isContact: (String) -> Boolean = { false },
    /** Opt-out group ids carried in multi-recipient bodies. */
    private val groups: com.personalos.app.data.sms.GroupStore? = null,
    /**
     * The outbox transition, injected rather than a DAO so this class keeps one
     * dependency on the store and tests need no fake. No-op by default.
     */
    private val markType: suspend (key: String, type: String) -> Unit = { _, _ -> },
    /**
     * The device's country, so a number has **one** identity: inbound arrives as
     * `+91…`, what we write is what the user typed. Empty means "leave alone".
     */
    private val countryIso: () -> String = { "" },
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

            // One classifier for the whole batch; its taxonomy is already stored.
            val classifier = categorizer()

            for (message in messages) {
                if (message.id > maxId) maxId = message.id

                val address = message.address ?: "Unknown"
                val body = message.body ?: ""
                val (cleanBody, groupId) =
                    com.personalos.app.data.sms.GroupTag
                        .strip(body)
                val ulid = Ulid.next()
                val type = typeName(message.type)
                // Only a **member's** reply joins the group. Our own fan-out copies
                // also come back through the provider, and attaching those would
                // record our text as a reply *from the recipient* — the mess where
                // one send produced eight "replies", all of them ours.
                if (groupId != null && type == "inbox") {
                    groups?.attach(groupId, ulid, address, cleanBody, message.date)
                }

                // Classify once, at ingest, so the category is queryable
                // (Radar filters on it) instead of recomputed per render. The
                // categorizer is the one owner of the category id.
                val category = classifier.categorize(address, cleanBody, isContact(address)).id

                events.add(
                    EventEntity(
                        ulid = ulid,
                        // Inbound: the provider row id *is* the identity, and there is no
                        // second writer. Outbound: ours and the provider's rows must be one
                        // fact, so both use the content key.
                        dedupeKey =
                            if (type == "sent") {
                                sentDedupeKey(address, cleanBody, message.date)
                            } else {
                                "sms:${message.id}"
                            },
                        source = SOURCE_ID,
                        type = type,
                        category = category,
                        timestamp = message.date,
                        title = PhoneNumbers.canonical(address, countryIso()),
                        content = cleanBody,
                        entities = "[]",
                        location = null,
                        url = null,
                        ingestedAt = ingestedAt,
                        sim = message.sim,
                    ),
                )
                tagInputs.add(TagInput(text = cleanBody, source = Transport.SMS, sender = address))
                fieldInputs.add(smsFields(address, cleanBody))
            }

            val inserted = land(events, tagInputs, fieldInputs)
            val newCount = inserted.size

            lastSeenId = maxId
            mark.write(maxId)
            Log.i(TAG, "SmsSource: ingested $newCount new SMS (scanned ${events.size}, lastSeenId=$maxId)")
            syncRecorder.record(SyncRun(SOURCE_ID, "sms", started, System.currentTimeMillis(), true, newCount, null))
            newCount
        }

    /**
     * Writes a just-sent message through the ingest store, so a thread this app
     * started shows its own outgoing side. `type = "sent"`, the destination is
     * the thread identity (`events.title`), and the row carries the same
     * the same [sentDedupeKey] the ingest path computes for an outgoing row, so
     * whichever writer lands first wins and the other is absorbed by
     * `unique(dedupe_key)` — a send is one fact, never two.
     */
    override suspend fun enqueueSent(
        destination: String,
        body: String,
        subscriptionId: Int?,
        timestampMs: Long,
    ): String =
        withContext(Dispatchers.IO) {
            val address = destination.ifBlank { "Unknown" }
            val (cleanBody, groupId) =
                com.personalos.app.data.sms.GroupTag
                    .strip(body)
            val ulid = Ulid.next()
            // Our own send is attached once, by the compose path, as from = "me" —
            // never here as if it were the recipient's reply.
            val category = categorizer().categorize(address, cleanBody, isContact(address)).id
            val event =
                EventEntity(
                    ulid = ulid,
                    dedupeKey = sentDedupeKey(address, cleanBody, timestampMs),
                    source = SOURCE_ID,
                    // The outbox: the row exists before the platform is asked, so the
                    // thread shows the message immediately and the UI never waits.
                    type = "queued",
                    category = category,
                    timestamp = timestampMs,
                    title = PhoneNumbers.canonical(destination, countryIso()),
                    content = cleanBody,
                    entities = "[]",
                    location = null,
                    url = null,
                    ingestedAt = System.currentTimeMillis(),
                    sim = subscriptionId?.toString(),
                )
            land(
                listOf(event),
                listOf(TagInput(text = body, source = Transport.SMS, sender = destination)),
                listOf(smsFields(address, body)),
            )
            event.dedupeKey
        }

    override suspend fun markResult(
        dedupeKey: String,
        sent: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            markType(dedupeKey, if (sent) "sent" else "failed")
        }
    }

    /**
     * Inserts the rows and runs the post-insert pipeline (tags, mentions, fields
     * then rules — rules read the store, ADR §13) for whatever actually landed.
     * The one path both [ingestNewMessages] and [recordSent] go through, so an
     * incoming and an outgoing message are stored, tagged and matched identically.
     * Returns the events that landed; duplicates (`-1`) are dropped.
     */
    private suspend fun land(
        events: List<EventEntity>,
        tagInputs: List<TagInput>,
        fieldInputs: List<Map<String, FieldValue>>,
    ): List<EventEntity> {
        val inserted = eventDao.insertAll(events)

        // Tag only what actually landed: duplicates come back as -1.
        val toTag = ArrayList<Pair<String, TagInput>>(events.size)
        val toFields = ArrayList<Pair<String, Map<String, FieldValue>>>(events.size)
        val landed = ArrayList<EventEntity>(events.size)
        inserted.forEachIndexed { index, insertedId ->
            if (insertedId == -1L) return@forEachIndexed
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
        return landed
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
