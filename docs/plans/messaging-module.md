# Messaging module — target design (hexagonal, send-capable, combinable)

> Design doc produced by @oracle (read-only). Nothing in this doc is implemented yet.
> Review this before P0. See §7 for the phasing.

Read: `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/CODE-DESIGN-GUIDELINES.md`, ADR 0006/0007/0008, PRD 0001, `Personal-information-aggregator.md`, and the live code (`data/SmsSource.kt`, `SmsReader.kt`, `SmsSyncMark.kt`, `Sql.kt` §conversations/search, `Migrations.kt` v19→v20, `core/SmsClassifier.kt`, `ui/messages/*`, `core/sources/*`, `data/adapters/*`, `core/AppContainer.kt`, `core/tile/*`).

---

## 0. Thesis (read this first — it changes the interface set)

Two product facts shape every decision below:

1. **Messaging is one combinable source among others.** The origin doc's own architecture is decisive: *"Once everything becomes an event… they stop being fundamentally different concepts."* Messages therefore stay `events` rows — the canonical item store stays the core, and the messaging module adds structured *message* facts around them. No `messages` table, no messaging-owned screen state. Any module can read messages through the same facts everything else reads.
2. **Actions are a first-class output.** Messages must be able to *propose* facts another module owns — a bill SMS becomes a **suggested reminder** the user confirms (this is exactly SMS Organizer's "classify, extract, turn into reminders", and PRD 0001's rule that derived things are *"never stored… until the user picks one"*). Proposals are domain rows with state (`SUGGESTED → CONFIRMED/DISMISSED`), not UI toasts.

**What this changes in the interface set, explicitly:**

- `MessageId` **is** `events.ulid` — the cross-module join key. Proposals, wallet entries, calendar links and reminders all reference the same fact id.
- The interface set gains a small, horizontal **Actions** core (own module, not inside messaging): `ActionProposal`, `ProposalSource`, `ActionStore`, `ActionConfirmer`. Messaging is its **first producer**, not its owner.
- `MessageStore.land` must return the landed item refs (so proposers run on what actually landed).
- `MessageSender` and the capabilities registry are **shared services**, not Messages-screen internals: an Agenda card or a notification quick-reply calls the same `enqueue`; a non-Messages surface asks the same registry whether a thread can be replied to.
- Messaging's read port (`ThreadReader`) is a query seam any surface may use, but nothing is required to depend on it to see messages — they are items, visible to Radar/rules/Search already.

---

## 1. What exists today (the load-bearing constraints)

| Fact | Today | Where |
|---|---|---|
| Message = item | `events` row, `source='sms'`, `title`=address, `content`=body, `dedupe_key='sms:<_id>'` | `data/SmsSource.kt:150-167` |
| Direction + delivery | conflated in `events.type` (`inbox/sent/draft/outbox/failed/queued`) | `SmsSource.typeName()` |
| Thread | `conversations` table keyed `(source, sender)`, maintained by 3 triggers scoped `source='sms'` | `Migrations.kt:679-825` |
| Ingest | one path, two callers (lifecycle observer + Worker) | `SmsSource`, `SmsSyncWorker` |
| Cursor | prefs high-water mark | `SmsSyncMark.kt` |
| Identity | raw sender string; contact name overlaid for display only | `contacts/ContactNames.kt` |
| Classification | SMS-specific nature/function classifier written to `events.category` | `core/SmsClassifier.kt` |
| Reads | SMS-hardcoded SQL (`source='sms'`), FTS search over `events(title,content)` | `Sql.kt:24-64, 507-613` |
| Source registry | `sources` row kind `sms` (identity `sms`); `KindAdapter` exists but SMS ingest is special-cased | `SourceSpecs.SmsSpec`, `SmsKindAdapter` (stub) |
| Rules/fields/tags | work off `events.ulid` | `item_tags`, `mentions`, `item_fields`, `item_rules` |

Constraints that must be preserved: one store; one-way layers; caps/formatting in UI; extend-don't-modify; small interfaces; Settings is a writer; Room can't validate triggers so schema tests must.

---

## 2. Core domain model and fact ownership

### 2.1 Model (pure Kotlin)

```kotlin
// :messaging — pure, no android.* imports (module like :mlsdk, see §5.6)

@JvmInline value class ProtocolId(val value: String)   // open string; constants, not an enum
@JvmInline value class ParticipantId(val value: String) // "tel:+919830611292", "mail:alex@x", "rooster:@alex@inst"
@JvmInline value class ThreadId(val value: String)
@JvmInline value class MessageId(val value: String)     // == events.ulid. The cross-module join key.
@JvmInline value class FactRef(val value: String)       // an item ulid; proposals stay source-agnostic

object ProtocolIds { val SMS = ProtocolId("sms"); val MMS = ProtocolId("mms"); val RCS = ProtocolId("rcs")
    val NEARBY = ProtocolId("nearby"); val EMAIL = ProtocolId("email"); val ROOSTER = ProtocolId("rooster") }

enum class Direction { INBOUND, OUTBOUND }

sealed interface DeliveryState {
    data object Received : DeliveryState                       // inbound; terminal unless a read receipt exists
    data object Draft : DeliveryState
    data object Queued : DeliveryState
    data object Sending : DeliveryState
    data class Sent(val at: Long?) : DeliveryState
    data class Delivered(val at: Long?) : DeliveryState
    data class Read(val at: Long?) : DeliveryState
    data class Failed(val reason: String, val retriable: Boolean) : DeliveryState
}

data class MediaRef(val kind: String, val mimeType: String?, val uri: String?, val bytes: Long?)

data class Message(
    val id: MessageId,
    val threadId: ThreadId,
    val protocol: ProtocolId,
    val direction: Direction,
    val timestamp: Long,
    val sender: ParticipantId,          // the local side is a lazily-created `local:<protocol>` participant
    val recipients: List<ParticipantId>,
    val body: String,
    val delivery: DeliveryState,
    val media: List<MediaRef> = emptyList(),
    val replyTo: MessageId? = null,
    val externalId: String? = null,     // transport id: SMS _id, mail Message-ID, rooster mid
)

data class Thread(
    val id: ThreadId,
    val protocol: ProtocolId,
    val title: String?,                 // group name / mail subject; null = name it from participants
    val participants: List<ParticipantId>,
    val lastAt: Long,
    val lastMessageId: MessageId?,      // join key for the preview, never a copy of the text
    val messageCount: Int,
    val pinned: Boolean,
)

/** A read projection: message + shared item facts a surface renders. Derived, never a second store. */
data class MessageView(
    val message: Message,
    val category: String?,              // SmsClass name today
    val saved: Boolean,                 // global bookmark fact, read-only here
    val tags: Set<String> = emptySet(),
)
```

**Direction is stored** (in `message_meta`, §5.1) rather than derived from sender-null: it is filtered on in every query, and a future multi-identity device (two Rooster handles) needs direction *per local identity*. Local identity rows are convention: `local:<protocol>`.

### 2.2 One fact, one owner (target vs today)

| Fact | Owner (target) | Owner (today) |
|---|---|---|
| Message text, timestamp, dedupe key, bookmark | `events` (one row per message) | same — **keep** |
| Direction | `message_meta.direction` | `events.type` (conflated) |
| Delivery state + failure | `message_meta.delivery`, `delivery_at`, `failure` | `events.type` (conflated) |
| Protocol per message | `message_meta.protocol` | implicit in `events.source` |
| Transport id (receipts, dedupe) | `message_meta.external_id` | `events.dedupe_key` only |
| Thread identity | `threads.thread_id` (protocol-scoped, stable) | `conversations` PK `(source, sender)` — wrong for groups |
| Thread rollup: last/at/counts/pin | `threads` (materialised; SQL triggers) | `conversations` |
| Thread membership | `thread_participants` | implicit (one sender) |
| Participant identity (id, handle, protocol) | `participants` | `events.title` only |
| Contact display name | `ContactNames` port (provider snapshot; never stored) | same — **keep** |
| Federated identity/key/trust | `identities` + `participants.trust` (phase 6) | — |
| Nature/function tags | `item_tags` (classifier/tagger, ADR 0007) | same |
| Disposition (muted/spam) | ADR 0007 DISPOSITION producer | ad hoc tabs |
| Sync cursor | `sync_cursors(source_id)` (one owner; today prefs) | `PrefsSmsSyncMark` |
| Sync history | `sync_runs` | same |
| Enable/interval | `sources` row per adapter | same (`sms` row) |
| Capabilities | the adapter (code, read-only) | n/a |
| Proposal state (suggested/confirmed) | `proposals` (shared Actions core) | — |

Two deliberate non-owners: direction/delivery are **not** mirrored into `events.type` after migration (that would be a drift-prone second copy); the legacy `type` reads die in the same slice (§7). And `item_fields` never gains `direction` — that would be a second owner.

---

## 3. Ports (concrete Kotlin)

All in `:messaging` unless noted. Signatures are the "right interfaces up front": adding a protocol must implement these and nothing else.

### 3.1 Transport ports

```kotlin
/** Reads messages from a transport. One reason to change: how that transport is read. */
interface InboundSource {
    val protocol: ProtocolId

    /**
     * Messages the transport can offer since [cursor] (adapter-interpreted, opaque).
     * At-least-once by contract: the store dedupes by (protocol, externalId), so a
     * re-read is safe. Null cursor = "from wherever the transport can start"
     * (LIVE_ONLY transports return only what happens from now on — an honest fact).
     */
    suspend fun fetch(since: String?): InboundResult

    /** Live arrivals, or null when the transport cannot push. Close to unregister. */
    fun observe(onArrival: () -> Unit): AutoCloseable?
}

sealed interface InboundResult {
    data class Messages(val messages: List<InboundMessage>, val next: String?) : InboundResult
    /** "Cannot read at all" (permission, disabled radio) — modelled, never an empty list. */
    data class Unavailable(val reason: String) : InboundResult
}

/** Sends over a transport. One reason to change: how that transport is sent to. */
interface OutboundSender {
    val protocol: ProtocolId
    suspend fun send(message: OutboundMessage): SendOutcome
}

/** Optional: transports that report delivery after acceptance (SMS PendingIntent, Rooster ack). */
interface DeliveryReporter {
    val protocol: ProtocolId
    fun deliveryUpdates(): Flow<DeliveryUpdate>
}

data class InboundMessage(
    val protocol: ProtocolId,
    val externalId: String,                 // protocol-scoped; becomes events.dedupe_key
    val direction: Direction,
    val timestamp: Long,
    val sender: ParticipantId,
    val recipients: List<ParticipantId>,
    val body: String,
    val media: List<MediaRef> = emptyList(),
    val delivery: DeliveryState,
    val replyToExternalId: String? = null,
    val nativeThreadId: String? = null,     // when the transport has one (mail root, RCS conv, Rooster conv)
)

data class OutboundMessage(
    val storeId: MessageId,                 // idempotency key: the events.ulid already written as Queued
    val threadId: ThreadId,
    val recipients: List<ParticipantId>,
    val body: String,
    val media: List<MediaRef> = emptyList(),
)

sealed interface SendOutcome {
    data class Accepted(val externalId: String?, val delivery: DeliveryState) : SendOutcome
    data class Rejected(val reason: String, val retriable: Boolean) : SendOutcome
}

data class DeliveryUpdate(val protocol: ProtocolId, val storeId: MessageId?, val externalId: String?, val state: DeliveryState)
```

`SendOutcome` models refusal rather than throwing (LSP per `CODE-DESIGN-GUIDELINES.md` §3 — "if an implementation genuinely cannot serve a case, that case belongs in the contract").

### 3.2 Capability descriptor + binding (the UI-hiding mechanism)

```kotlin
enum class ReadCapability { FULL, LIVE_ONLY, NONE }

data class MessagingCapabilities(
    val protocol: ProtocolId,
    val read: ReadCapability,          // FULL = history + live; LIVE_ONLY = only while observing; NONE
    val canSend: Boolean,
    val canGroup: Boolean,
    val canMedia: Boolean,
    val maxTextLength: Int? = null,    // null = no hard limit (SMS = segmented; see §4 notes)
    val deliveryReceipts: Boolean = false,
    val readReceipts: Boolean = false,
    val networkRequired: Boolean = false,
    val storeAndForward: Boolean = false,
)

/** One protocol's wiring, built in AppContainer. The single place a protocol is declared. */
data class ProtocolBinding(
    val protocol: ProtocolId,
    val capabilities: MessagingCapabilities,
    val inbound: InboundSource? = null,
    val outbound: OutboundSender? = null,
    val delivery: DeliveryReporter? = null,
)

/** The single lookup for capabilities — used by any surface, not just Messages. */
class MessagingRegistry(private val bindings: Map<ProtocolId, ProtocolBinding>) {
    init { require(bindings.values.all { it.capabilities.protocol == it.protocol }) }
    fun binding(protocol: ProtocolId): ProtocolBinding?
    fun capabilities(protocol: ProtocolId): MessagingCapabilities?   // null = unknown protocol: read-only fallback
    fun readable(): List<ProtocolBinding>
    fun sendable(): List<ProtocolBinding>
}
```

**The UI never branches on protocol.** It reads capabilities: compose bar iff `canSend`, attach iff `canMedia`, char counter iff `maxTextLength != null`, "read-only" badge iff `read == LIVE_ONLY`, "offline send will queue" iff `storeAndForward`. Two guards: (a) a registry completeness check at construction (every binding has a descriptor); (b) an architecture test that `ui/messages/**` contains no `ProtocolIds.*` literal — adding a protocol can never force a UI edit.

Thread-level rules that are not protocol constants (no reply to a DLT business header, no compose in a read-only thread) are a pure predicate in `:messaging`:

```kotlin
// messaging/logic/ComposePolicy.kt — pure, testable
object ComposePolicy {
    fun canReply(caps: MessagingCapabilities, thread: Thread, participants: List<Participant>): Boolean
    fun composeProtocols(registry: MessagingRegistry): List<MessagingCapabilities>
}
```

### 3.3 Storage ports (split by role — the "ConversationStore" answer)

A single `ConversationStore` would serve two consumers with different reasons to change, so it splits by role (ISP):

```kotlin
/** Reads for any surface. Never writes, never caps for display. */
interface ThreadReader {
    fun observeThreads(filter: ThreadFilter): Flow<List<Thread>>
    fun observeThread(id: ThreadId): Flow<Thread?>
    fun messages(id: ThreadId, before: MessageCursor?, limit: Int): Flow<List<MessageView>>
    fun threadFor(message: MessageId): Flow<ThreadId?>     // an Agenda row opens the thread it came from
}

/** Writes every message fact. The one choke point all adapters land through. */
interface MessageStore {
    suspend fun land(messages: List<InboundMessage>): Landing            // returns landed refs for proposers
    suspend fun createOutbound(draft: OutboundDraft): Message            // direction=OUT, delivery=Queued
    suspend fun updateDelivery(message: MessageId, state: DeliveryState)
    suspend fun setPinned(thread: ThreadId, pinned: Boolean)
    suspend fun remove(message: MessageId)                               // app copy only, as today
}

data class Landing(val added: List<MessageId>, val skipped: Int)
data class OutboundDraft(val protocol: ProtocolId, val threadId: ThreadId?, val recipients: List<ParticipantId>,
                         val body: String, val media: List<MediaRef> = emptyList())

/** Search across every protocol. Separate port: a different technology (FTS), a different reason to change. */
interface MessageSearch { suspend fun search(query: String, limit: Int): List<MessageView> }

/** Opaque per-source cursors; the app persists, adapters interpret. */
interface SyncCursorStore { fun get(sourceId: String): String?; fun set(sourceId: String, cursor: String?) }
```

### 3.4 Identity port

```kotlin
data class Participant(val id: ParticipantId, val protocol: ProtocolId, val handle: String,
                       val displayName: String?, val trust: Trust)
enum class Trust { UNKNOWN, KNOWN, VERIFIED }

interface ParticipantDirectory {
    fun resolve(id: ParticipantId): Participant?
    fun observe(id: ParticipantId): Flow<Participant?>
}
```

`ContactNames` (existing, `data/contacts/ContactNames.kt`) stays exactly as it is — the display-name overlay, honest fallback, no second owner of identity. `ParticipantDirectory` is the app-side union of participants rows and (later) federated identity.

### 3.5 Core services (logic, not ports)

```kotlin
/** Fetch → land → proposers. One per run; steps idempotent (ADR 0008). */
class MessageIngest(registry: MessagingRegistry, store: MessageStore, cursors: SyncCursorStore) {
    suspend fun fetch(sourceId: String, protocol: ProtocolId): IngestReport
    suspend fun fetchAll(): IngestReport
}

/** Enqueue → drain → delivery. Shared by every surface; safe across process death. */
class MessageSender(registry: MessagingRegistry, store: MessageStore) {
    suspend fun enqueue(draft: OutboundDraft): MessageId
    suspend fun flush()                                  // drains Queued; the DAG's outbox step
    suspend fun onDelivery(update: DeliveryUpdate)
}
```

State machine (no ambiguous states, ADR 0008 §6): `Draft → Queued → Sending → Sent → Delivered → Read`, `→ Failed(retriable|permanent)`. **A message found `Sending` after a restart becomes `Failed("outcome unknown — not resent")` and is surfaced**: a duplicate SMS is worse than a failed one. No silent retry.

### 3.6 Actions (own module — the "suggested vs confirmed" interface)

```kotlin
// :actions — pure, no android.*; messaging/calendar/wallet are producers, not owners.

@JvmInline value class ProposalId(val value: String)
enum class ProposalState { SUGGESTED, CONFIRMED, DISMISSED, EXPIRED }

data class ActionProposal(
    val id: ProposalId,
    val kind: String,                 // ActionKinds.REMINDER | CALENDAR_EVENT | WALLET_ENTRY — data, open set
    val subject: FactRef,             // the message it was derived from (events.ulid)
    val producer: String,             // "sms:bill-due:v1" — provenance, like taggerId
    val title: String,
    val dueAt: Long? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val state: ProposalState,
    val confirmedRef: FactRef? = null, // what confirmation created (reminder id) — the cross-module link
    val createdAt: Long,
    val stateAt: Long,
)

/** A pure producer: facts in, proposals out. Idempotent by (subject, kind); a re-run cannot resurrect a dismissal. */
interface ProposalSource { val id: String; fun propose(item: ItemFacts): List<ActionProposal> }

/** What a proposer may read: facts, not text. Assembled by the DAG step from the store. */
data class ItemFacts(val item: FactRef, val source: String, val timestamp: Long, val title: String, val body: String,
                     val category: String?, val fields: Map<String, FieldValue>, val tags: Set<String>)

/** One owner of proposal state. App-provided Room adapter (or prefs for v0). */
interface ActionStore {
    fun observe(state: ProposalState?): Flow<List<ActionProposal>>
    fun observeFor(subject: FactRef): Flow<List<ActionProposal>>   // a message row shows its own suggestions
    suspend fun put(proposals: List<ActionProposal>)               // INSERT-if-absent per (subject, kind, producer)
    suspend fun confirm(id: ProposalId): FactRef?                  // via the confirmer, links result
    suspend fun dismiss(id: ProposalId)
}

/** One per kind: turns a confirmed proposal into the owning module's fact. The OCP seam for new action kinds. */
interface ActionConfirmer { val kind: String; suspend fun confirm(proposal: ActionProposal): FactRef }

object ActionKinds { const val REMINDER = "reminder"; const val CALENDAR_EVENT = "calendar_event"; const val WALLET_ENTRY = "wallet_entry" }
```

This is domain data, not UI: the same proposal renders as a chip on a message row, a card on an Agenda surface, or a line in Settings — all reading `ActionStore`. v1 proposer: `sms:bill-due:v1` (fires on `SmsClass.BILL` + a confidently parsed due date; **no date is better than a wrong date**, per PRD 0001's ethos). First confirmer: `REMINDER` → a minimal reminders row (title, dueAt, subject ref); when PRD 0001 v4/v6 lands, the reminder becomes the calendar's personal event and the confirmer changes — proposals don't.

---

## 4. Adapters and exactly what each can do

One class may implement several ports (an adapter is a transport, not an interface role). All live in `data/messaging/` (Android allowed), bound in `AppContainer` only.

| Protocol | `read` | `canSend` | `canGroup` | media | receipts | store-and-forward | permissions / access | Impossible (do not design toward it) |
|---|---|---|---|---|---|---|---|---|
| **SMS** (`sms`) | FULL (provider poll + content observer) | yes (`SmsManager`; per-subscription id) | no | no | delivery best-effort (`deliveryIntent`); no read receipts | n/a (instant) | `READ_SMS`, `SEND_SMS` | Receive broadcasts (only the default SMS app gets `SMS_DELIVER` since 4.4 — poll only); write to `content://sms` (sent rows invisible to the system Messages app); mark read |
| **MMS** (`mms`) | PARTIAL (`content://mms`; group threads may need default-app role) | **no (v1)** | read-if-possible | receive-only | no | n/a | same as SMS + `[RESEARCH]` | Reliable send/group without the default role; media fetch on some OEMs |
| **RCS** (`rcs`) | **LIVE_ONLY** (`NotificationListenerService`; no history) | **no** | no (membership invisible) | no | no | no | user-granted notification access + `POST_NOTIFICATIONS` | Everything else: no send API, no history, no group membership, no receipts; dedupe is heuristic (notification key + timestamp); read-only by construction |
| **Nearby** (`nearby`, Wi-Fi Direct) | LIVE_ONLY (peer must be in range *now*) | yes (socket) | **no (v1)** | no (v1) | local ack (`delivered`) | **no** — a peer offline means "not sent", honest | `NEARBY_WIFI_DEVICES` (13+), location on some versions | Store-and-forward; iOS; discovery beyond range; internet-independent messaging to an absent peer |
| **Email** (`mail:<account>`) | FULL (IMAP poll; no push without IDLE `[RESEARCH]`) | yes (SMTP queue → retry via outbox) | yes (To/Cc) | attachments later | MDN rare; off | yes (own queue) | user account + credentials ⇒ **new secret storage territory** `[RESEARCH]` | Push without keeping a socket/IDLE; OAuth-free reading on providers that kill app passwords |
| **Rooster** (`rooster:<handle>`) | FULL (relay sync) | yes | yes | later | yes (signed acks) | yes (relay) | network; optional relay server — the only protocol we own end-to-end | Federation discovery/global search; relay-free delivery to an offline peer |

Notable per-adapter designs:

- **SMS send** must use `SmsManager.createForSubscriptionId` (dual-SIM) and a `PendingIntent` per message; `DeliveryReporter` wraps those broadcasts. `maxTextLength` stays `null` (multipart send is supported; billed per segment — a `notes`-style fact belongs in the descriptor, not the UI).
- **RCS** is the canonical read-only adapter and the first proof that the capability descriptor hides UI automatically: it appears in thread lists, never in `sendable()`.
- **Nearby** is the canonical ephemeral adapter: its `fetch` can only return what happened while observing; queued messages retry when the peer reappears (`flush` honours `storeAndForward=false` by failing loudly after a timeout, not silently).
- **Email** is the first adapter needing credentials and a network queue; it validates `SyncCursorStore` and the outbox step.
- **Rooster** is the only adapter where identity is a keypair and the wire format is ours; it depends on §6.

---

## 5. Integration with the existing architecture

### 5.1 Schema: extend `events`; replace `conversations`; add four tables

`events` is untouched — it stays the one owner of the message item. The messaging module's facts go in a sidecar plus thread tables (Room can't model `CHECK`/triggers, so they are enforced by the codec/`DERIVED_OBJECT_STATEMENTS` like today's v20 triggers):

```sql
-- one row per message item: the messaging sidecar
CREATE TABLE message_meta (
  item_id     TEXT PRIMARY KEY REFERENCES events(ulid) ON DELETE CASCADE,
  protocol    TEXT NOT NULL,
  direction   TEXT NOT NULL,              -- 'in' | 'out' (codec-validated; no CHECK, Room would drift)
  thread_id   TEXT NOT NULL,
  external_id TEXT,
  delivery    TEXT NOT NULL,              -- DeliveryState codec
  delivery_at INTEGER,
  failure     TEXT,
  reply_to    TEXT
);
CREATE INDEX idx_message_meta_thread   ON message_meta(thread_id, item_id);
CREATE INDEX idx_message_meta_external ON message_meta(protocol, external_id);

CREATE TABLE threads (
  thread_id       TEXT PRIMARY KEY,       -- 'sms:+9198…' | 'mail:<root-id>' | 'rooster:<conv>' — adapter/core computed
  protocol        TEXT NOT NULL,
  title           TEXT,
  last_at         INTEGER NOT NULL,
  last_message_id INTEGER NOT NULL,
  message_count   INTEGER NOT NULL,
  in_count        INTEGER NOT NULL,
  out_count       INTEGER NOT NULL,
  pinned          INTEGER NOT NULL
);

CREATE TABLE thread_participants (
  thread_id      TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  PRIMARY KEY (thread_id, participant_id)
);

CREATE TABLE participants (
  id         TEXT PRIMARY KEY,            -- normalized, e.g. 'tel:+919830611292'
  protocol   TEXT NOT NULL,
  handle     TEXT NOT NULL,               -- as first seen; display fallback
  first_seen INTEGER NOT NULL
);

CREATE TABLE proposals (                  -- the Actions core (own module)
  id            TEXT PRIMARY KEY,
  kind          TEXT NOT NULL,
  subject_id    TEXT NOT NULL,            -- events.ulid
  producer      TEXT NOT NULL,
  title         TEXT NOT NULL,
  due_at        INTEGER,
  amount        REAL,
  currency      TEXT,
  state         TEXT NOT NULL,
  confirmed_ref TEXT,
  created_at    INTEGER NOT NULL,
  state_at      INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_proposals_identity ON proposals(subject_id, kind, producer);
CREATE INDEX idx_proposals_state ON proposals(state, due_at);
```

**Maintenance split — the one rule that keeps this sane:** Kotlin computes *identity* (normalized participant ids, thread ids, native thread keys); SQL triggers maintain *rollups only*. A trigger on `message_meta` INSERT upserts `threads` (`MAX` semantics, joined to `events` for `timestamp`); one on DELETE recomputes and removes empty threads. Triggers cannot normalize phone numbers; writers cannot be trusted to update counts. Each does what it is good at. The v20 contract survives: `events`/`message_meta` are the owner, `threads` is derived.

`conversations` is **replaced**, not extended: its PK `(source, sender)` cannot express groups (many senders), native thread ids (mail/RCS/Rooster), or protocol-scoped identity. Migration is a table rebuild either way; take the correct key.

### 5.2 Sources, kinds, families (ADR 0006 / 0008)

- One `sources` row per adapter/account: seeded `sms` (exists), `rcs`, `nearby`; user-added `mail:<account>`; user-added `rooster:<handle>`. `events.source` identity comes from `SourceKeys` (extend with the same `when`-over-spec pattern — the codebase's existing data-extension point). Protocol may differ per message inside one source (`sms` source carries `sms` and `mms`).
- Each messaging adapter also gets a thin `KindAdapter` **facade** delegating `ingest` to `MessageIngest.fetch(sourceId, protocol)`, so the Sources index, `sync_runs` health, enable/disable and the generic dispatch loop work unchanged — one facade, not a second registry.
- **Fix now, or protocol #2 breaks OCP:** `RetagFamilies.ALL` is a hardcoded list (`data/work/RetagFamilies.kt:11`). Derive families from `sources` identities (prefix before `:`) so a new protocol joins retagging without editing code.
- **DAG (ADR 0008):** nodes `messages.fetch` (per bound source; depends on the `ingest` node), `messages.outbox.flush` (depends on nothing — safe to run any time), `actions.propose` (depends on `messages.fetch`; reads items with no proposals from the active producer, mirrors the mentions backfill's `… NOT IN (…)` + cursor shape). Delivery observers are app-scope collectors, not steps. `SmsSyncWorker` becomes the fetch step's background executor.
- `TagInput.transport`'s closed enum (`SMS/RSS/JSON/WEB`) becomes a protocol/source string, or the tagger gains priors from the taxonomy — otherwise every protocol is an enum edit (same OCP trap as `RetagFamilies`).

### 5.3 Reads: delete the SMS-hardcoded SQL in the same slice

`Sql.kt`'s `EVENTS_COUNT_BY_MODE`, `EVENTS_PAGE_BY_MODE`, `CONVERSATIONS_*`, `EVENTS_THREAD_PAGE` (thread by `title = :sender`), and the `source='sms'` scoping in `EVENTS_SEARCH_MATCH`/`LIKE` all die. Replacements:

- Thread page: `message_meta.thread_id` join, keyset `(timestamp, id)` as today.
- Search: scope by `JOIN message_meta m ON m.item_id = e.ulid` — **messages of every protocol, no protocol list in SQL**; adding a protocol changes no query. FTS5 trigram index over `events(title, content)` is unchanged.
- Change signal for the screen: `SELECT MAX(rowid) FROM message_meta` (a real message landed), replacing `observeMaxIdForSource("sms")` — still scoped, now protocol-agnostic.
- The Messages tabs become All / (Unread if ever knowable) / Saved; `Alerts/Digest/Muted` were dispositions (ADR 0007), and Saved stays the **global bookmark fact** — not a messaging copy. **Unread is not claimed**: a non-default app cannot mark SMS read, and the provider's `read` flag is owned by the default app; the badge is `[RESEARCH]`, not v1.

### 5.4 What the module does not touch

Rules/tags/mentions/fields keep working off `events.ulid` untouched. v1 gains **no** rule predicate on direction/delivery (a predicate would need `message_meta` in the evaluator; note it as a deliberate limit, not an oversight). Retention stays tag-based (`personal` retention should protect messages — flag, out of scope).

### 5.5 Settings is a writer

Per-adapter surface = `sources.enabled` (stored, existing) + capability descriptor + derived status (`NEEDS_PERMISSION`, `UNAVAILABLE`). Enabling an adapter is the user action that requests its permission (SMS/contacts/notifications/nearby); disabling stops ingest/observe without deleting facts. Nothing user-editable is a literal in code; no screen learns a protocol constant.

### 5.6 Packaging: two small modules, not one big feature

`android/settings.gradle.kts` already proves the pattern: `:mlsdk` exists "so it can be lifted into a hub app unchanged." Do the same:

- `:messaging` — pure Kotlin (model, ports, registry, ingest/send logic, ComposePolicy). Compile-time enforcement of "no android.\*" is the strongest guard that the core never acquires a platform dependency.
- `:actions` — pure Kotlin, depended on by `:messaging` (never the reverse); calendar/wallet later depend on it too.
- Adapters and Room implementations stay in `:app` (`data/messaging/`). Extraction later moves adapters per host — say this honestly: the *core* is liftable unchanged; each host supplies its own store and transports. Don't pretend the whole feature extracts with zero host code.

---

## 6. Identity

**Minimal version first (phases 1–3):** `participants` rows are created on landing from the handles adapters supply (no READ_CONTACTS needed); display names come from the existing `ContactNames` overlay; `senderMatchKey` stays the contact-matching rule only, never storage identity. Participant ids are normalized by a pure `HandleNormalizer` (strip separators; `tel:` keeps the number as given — **no silent +91 assumption**, and no irreversible 10-digit collapsing into stored identity). Migration keeps today's grouping exactly (`thread_id = 'sms:' + raw title`); any later re-grouping is an explicit, announced step — never a silent side effect of a migration.

**Federated (phase 6, sketch only):** identity is a **keypair on the device** + a handle `@user@instance` — the relay is just another adapter, so a server being down or absent never changes who you are. New rows: `identities(id, protocol, handle, public_key, secret_ref)` (secret in Keystore), `participants.trust`, and a `friend_requests(id, from, to, state, created_at)` state machine (`NONE/REQUESTED/INCOMING/ACCEPTED/BLOCKED`) — the Mastodon-style follow seen as a message in the same inbox. Do not build federation/discovery; v1 is direct messages between two devices with handles, over the shared relay. The user's "friend request" idea lands as: an inbound Rooster proposal/message of kind `friend_request`, confirmable from the same Actions store.

---

## 7. Migration path and phasing (what to build first)

The interfaces in §3 are the deliverable that must exist before any second protocol, and they are cheap: phase 0 changes no schema and no pixels.

| Phase | Content | Exit test |
|---|---|---|
| **P0 — Ports + SMS behind them** (no schema, no visible change) | `:messaging` + `:actions` modules; `SmsInboundSource` wraps `SmsReader`+`SmsSyncMark`; `SmsOutboundSender` (`SmsManager` + `PendingIntent` → `DeliveryReporter`); `MessagingRegistry` with the `sms` binding only; `ThreadReader`/`MessageStore` implemented over **today's** `events`+`conversations` (direction from `type`, protocol=`sms`); `MessageIngest`/`MessageSender` orchestrators with unit tests | The current Messages screen still passes untouched; new send path flag-gated; capability + state-machine + LSP fake-adapter tests |
| **P1 — Messaging schema + send UI, one slice** | v21: `message_meta`, `threads`, `thread_participants`, `participants`, `sync_cursors`; backfill from `events`+`conversations`; drop `conversations`+its triggers; triggers on `message_meta`; `MessagesScreen`/`ConversationThreadScreen` rewritten through `ThreadReader` + compose bar through `MessageSender`; delete all legacy SQL in the same commit | `MigrationSchemaTest` + trigger tests; search returns messages of both a seeded SMS row and a synthetic second protocol row; no `source='sms'` remains in `Sql.kt` message reads |
| **P2 — Actions seam** | `proposals` table + `ActionStore`/`ProposalSource`/`ActionConfirmer`; `actions.propose` DAG node; `sms:bill-due:v1`; confirm → minimal reminder; suggestion chip on message rows and on Home | A bill SMS yields exactly one SUGGESTED proposal; dismiss → re-run cannot resurrect; confirm → reminder row + `confirmed_ref` |
| **P3 — RCS (read-only)** | `NotificationListenerService` adapter + binding with `canSend=false`, `read=LIVE_ONLY`; dedupe heuristic; source row | Compose is hidden automatically by capability; no core/UI code changed except the adapter + binding |
| **P4 — Email** | IMAP read (cursor), SMTP outbox send, RFC 5322 threading; account spec + secret storage decision | Offline send queues and flushes; search covers mail via the same join |
| **P5 — Nearby** | Wi-Fi Direct discovery + sockets; ack receipts; range/absence honesty in UI | `storeAndForward=false` surfaces as "not sent" not "sent" |
| **P6 — Rooster + identity** | keypair identity, friend requests, relay sync (relay optional) | Two devices exchange a DM; identity survives relay loss |

Sequencing rationale: P0 proves the seams; P1 proves the schema; **P3 proves read-only adapters hide UI automatically (the user's requirement)**; P4 proves network/queue; P5 proves ephemeral; P6 proves identity. Each phase after P1 adds *one* adapter and touches no core file — that is the acceptance test for "the core does not keep changing."

---

## 8. Risks and trade-offs

1. **P1 is a big single slice** (schema + triggers + screen + SQL + search scoping). That is this repo's own rule — half-done leaves two owners of thread state — but do it in a worktree, and snapshot the DB before the backfill (WAL + `-wal`, per `AGENTS.md`).
2. **Trigger invisibility** (Room can't validate): keep the v20 discipline — one spelling in `Migrations.kt`, `DERIVED_OBJECT_STATEMENTS` for fresh installs, explicit assertions in `MigrationSchemaTest`, and a trigger test like `ConversationTriggerTest` replaced by `ThreadTriggerTest`.
3. **Re-grouping threads is irreversible user-visible data.** P1 preserves today's raw-address grouping; normalization is a later, explicit step with a before/after count to verify.
4. **Capability drift** (an adapter claims `canSend` but the transport fails) — the descriptor is coarse; keep it honest by adapting it at binding time (email without SMTP creds → `canSend=false`) and test the matrix: every `ProtocolIds` constant has a binding, every binding a full descriptor.
5. **NotificationListener fragility for RCS** — extras change across OEM/app versions; dedupe is heuristic; label it LIVE_ONLY and never let the UI imply completeness.
6. **Secrets (email, Rooster keys) are new territory** — no precedent in the repo; decide one Keystore-backed owner before P4/P6.
7. **Two registries (KindAdapter map + MessagingRegistry) could drift** — the KindAdapter is a facade over the registry; assert in wiring that every messaging KindAdapter resolves to a binding.
8. **The sidecar can drift from `events`** (a message row without meta). Tests: every event whose source is a messaging identity has exactly one `message_meta` row; `remove()` deletes both in one transaction (FK cascade + test).
9. **`events.type` legacy readers** — if any survive P1 they will lie about delivery. Delete them in the same slice; rg for `EVENTS_*_BY_MODE`/`CONVERSATIONS_` as the checklist.
10. **Library extraction is not free** — core is; adapters and Room store are per-host. Keep adapters free of `events`-shaped assumptions (they speak `InboundMessage`/`OutboundMessage` only), or extraction gets expensive.
11. **Home/Agenda combination is the real prize** — keep proposals and `MessageId = events.ulid` disciplined, or the first cross-module feature bends the model.

---

## 9. Explicitly impossible (and therefore non-goals)

- **Sending RCS.** No public API; notification direct-reply is app-specific and unstable — not a designed capability. RCS is read-only forever in this design.
- **RCS history, group membership, receipts, media.** Notifications carry text only; no thread model, no membership; dedupe is heuristic.
- **Being the default SMS app's equal without being the default SMS app.** No `SMS_DELIVER` broadcast (poll only), no writes to `content://sms` (sent items invisible to the system Messages app), no marking read, no group MMS guarantees.
- **Guaranteed SMS delivery/read receipts.** `deliveryIntent` is best-effort at best.
- **MMS send in v1.** Deferred, not promised.
- **Email push without a persistent socket, and OAuth-free reading where providers disable app passwords.** `[RESEARCH]`; polling is the design.
- **Nearby store-and-forward, iOS peers, or messaging an absent peer.**
- **Rooster federation discovery/global search, and relay-free delivery to an offline peer.**
- **A global, cross-source search.** Search stays the Messages scoped search (now multi-protocol), per `Sql.kt`'s deliberate no-global-search decision.
- **Unread badges for protocols that cannot report read state** (SMS today).

**Anti-goals for the interface set:** a `MessagingAdapter` god-interface; protocol branches in UI; a `messages` table duplicating `events`; a second copy of direction in `item_fields`; a messaging-owned "saved"; auto-resending an ambiguous `Sending`; and treating the Messages screen as the owner of threads — it is a view.
