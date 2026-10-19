# ADR 0011: Contact identity — senders, links, and birthdays as a projection

- Status: Proposed
- Date: 2026-10-19
- Related: `docs/ARCHITECTURE.md` (§events, §conversations), `docs/adrs/0006-sources-unification.md`, `docs/prd/0001-calendar-travel-and-personal-events.md`
- Supersedes: nothing. Extends `0006` by giving a *person* an identity, the way `0006` gave a source one.

## Summary

A conversation's identity today **is its display text**: `conversations` is keyed
`(source, sender)` and `sender` is the raw phone number as received
(`ConversationEntity.kt:23-26`). There is no contact entity, no id, and nothing
for `sender` to be a foreign key to — so "the same person" has no representation,
and every attempt to merge one is **string surgery over message rows**.

This ADR introduces the missing entity and refuses the surgery. Three tables —
`contacts` (the profile), `senders` (an address with an id), `contact_links`
(sender → contact) — give one person one id. `events` gains a `sender_id`
pointer, so identity stops being a string and **no message row ever moves**. A
birthday is stored on the contact and **projected** into the calendar at read
time, never copied into `calendar_dates`, because that table is feed-owned and
pruned by `fetched_at` (`CalendarDao.deleteStaleForSource`) — a copied birthday
would be deleted by an unrelated sync.

## The problem, with evidence

1. **Identity is a display string.** `ConversationEntity` declares
   `primaryKeys = ["source", "sender"]`; `EventEntity.title` is the sender. One
   fact — *who this is* — is stored as the label that is shown. Renaming is
   therefore a retitle of every message (`UPDATE events SET title = …`), which
   costs O(messages), resets `pinned` in the `conversations_au` re-aggregate, and
   cannot be undone.
2. **`sender` cannot be a foreign key**, because there is nothing to point at:
   no `contacts`, `people` or `addresses` table exists. The string *is* the
   entity.
3. **"Same person" is guessed, twice, differently.** `senderMatchKey` matches on
   the **last 10 digits** (`ContactNames.kt:12`), a fixed width that is a fact
   about India, not about numbers — `+44 98765 43210` and `+91 98765 43210`
   collide, and a 12-digit national number truncates its own country code. The
   canonicaliser added this session (`PhoneNumbers.canonical`) computes a
   *different* key. Two definitions of "same number" already exist.
4. **The same person is N rows.** A phone number and an email address for one
   person are, by construction, unrelated senders — `source` (ADR 0006) namespaces
   them apart, and nothing joins them. Cross-source duplication is structural, not
   a bug.
5. **A contact field has no owner.** Names come from the device provider, read
   live into a cache (`AndroidContactNames.refresh`), and written back with
   `create(name, number, google)` (`AndroidContactWriter.kt:21`) — one number. A
   user-edited name has nowhere to live, so a provider refresh can win.
6. **Recurring dates have no form.** `CalendarDateEntity.starts_at` is an absolute
   epoch (`CalendarDateEntity.kt:46`). A birthday recurs; it can neither be stored
   absolute nor inserted into `calendar_dates` (see Summary).

## Decision

### 1. One person, one id; one address, one id

```
contacts(id PK, display_name, given_name, family_name, organization, job_title,
         photo_uri, birthday, notes, name_source, is_self, created_at, updated_at)

senders(id PK, source, address, match_key, kind, primary_order, UNIQUE(source, address))

contact_links(sender_id PK, contact_id, kind, created_at)
```

`contact_links.sender_id` is a **primary key**: a sender belongs to exactly one
contact; a contact has N senders. That single invariant is the model. "Connect
this person's email to their phone" is one row here and **nothing else in the
database changes**.

`kind` records *how* the link was made — `duped` (the rule: one number, two
formats), `linked` (a claim), `imported` (from the provider) — so it is
reversible and auditable.

### 2. Every sender has a contact from birth

A sender seen in a message is auto-linked to a **nameless** contact on first
sight. Unlinked is then *a contact with no name*, not a null join or a string
compare, and every read is one `GROUP BY contact_id` with no `COALESCE` and no id
collisions. Names arrive later and fill in.

### 3. Identity moves to a pointer; observations do not move

`events` gains a nullable `sender_id`. `title` stays exactly as received — it is
an observation and the display string. Backfill is one `UPDATE … FROM`; after
that, **no merge rewrites a message row**.

### 4. `conversations` is not re-keyed (deliberately)

The trigger-maintained aggregate keeps `(source, sender)`, so its triggers, its
counts and `pinned` are untouched. Contact-level grouping is a **read**:

```sql
SELECT l.contact_id AS person, SUM(c.msg_count), MAX(c.last_at)
FROM conversations c
JOIN senders s ON s.source = c.source AND s.address = c.sender
JOIN contact_links l ON l.sender_id = s.id
GROUP BY person
```

This is the rule (3: display grouping belongs to the read) applied to the merge.
Re-keying `conversations` to `sender_id` is a *later* slice, taken only if a
measurement asks for it; it rewrites every trigger and is not free.

### 5. One owner for the name; a source seeds it, a user overrides it

`contacts.name_source` records provenance: `none | device | google | user`. The
**rule**: an import may write `device`/`google`, and may never overwrite `user`.
This is what keeps "one fact, one owner" true while the provider still seeds —
the provider is a *source* (ADR 0006), not a second copy. The live read
(`AndroidContactNames`) is replaced by a DB lookup; the provider read becomes the
importer.

### 6. The identity rule is injectable, and there is only one

```kotlin
fun interface NumberIdentity {
    /** The key two senders are the same number by; null = no opinion, never links. */
    fun key(address: String?): String?
}
```

Default `CountryCodeIdentity(homeCountry)` — *country code + national*, as the
user stated: strip the known country code, strip a trunk `0`, compare. It is
length-agnostic (fixes evidence 3) and country-aware (fixes the `+44`/`+91`
collision). `null` for anything it cannot canonicalise (short code, `VM-HDFCBK-S`)
so those can never link — "never invent" becomes structural. `senderMatchKey` is
retired: the import matches provider rows through **the same rule**, so "same
number" has one definition.

### 7. The merge is one statement, and it does not move data

`senders.match_key` stores the rule's output for a sender — a deliberate,
*named* materialisation, because it is the join key of the whole merge and it is
peer-sized. The merge is then idempotent:

```sql
INSERT OR IGNORE INTO contact_links (sender_id, contact_id, kind, created_at)
SELECT s.id, :primary_contact_id, 'duped', :now
FROM senders s
WHERE s.match_key IN (SELECT match_key FROM senders GROUP BY match_key HAVING COUNT(*) > 1)
```

The rule changed → the same job recomputes `match_key` and re-runs; that is the
entire invalidation story. Cost is O(senders), not O(messages).

### 8. A birthday is projected into the calendar, never stored in it

`contacts.birthday` is the fact and its single owner. `--MM-DD` when the year is
unknown, `YYYY-MM-DD` otherwise. The calendar read becomes the merge of dated
**sources** — the feed rows and the birthdays:

```kotlin
interface CalendarSource {                       // core/calendar
    fun within(fromMs: Long, toMs: Long): Flow<List<CalendarEntry>>
}
```

- `FeedCalendarSource` — today's `CalendarDao.observeUpcoming` (`Sql.CALENDAR_UPCOMING`).
- `BirthdaySource` — `contacts` with a non-null `birthday`, **projected into the
  requested window**: `month/day` is placed in each year the window spans, and
  `starts_at` is computed by the existing `CalendarDates.toEpochMsUtc`.

`CalendarKind` gains `BIRTHDAY`. Nothing is written to `calendar_dates`, so no
sync can delete a birthday, and editing a birthday is one `UPDATE contacts`.
Recurrence is a *projection*, which is the only form that survives a window that
crosses a year boundary.

### 9. It is a job, not a screen

Three callers of the same functions — matching how `EnrichWorker` runs the
enrichment — so this is testable and shell-invokable:

- `ContactImportWorker` — reads `ContactsContract`, upserts `contacts`/`senders`,
  links, honours §5 precedence. Scheduled like the feed sync.
- `NumberLinks.refresh()` — §7.
- `ContactBackfill` — one-shot per rule change / upgrade (precedent:
  `RuleBackfillScheduler`, `CategoryBackfill`).

## Invariants

- **I1** A sender belongs to exactly one contact (`contact_links.sender_id` PK).
- **I2** Identity is never derived from `title` at write time; `events.title` is
  display only.
- **I3** A merge never modifies `events` or `conversations` rows.
- **I4** `name_source = 'user'` is never overwritten by an import.
- **I5** `calendar_dates` rows are written only by feed syncs.
- **I6** "Same number" is `NumberIdentity.key`, and only that.

## Schema — migration 22 → 23

`AppDatabase.version = 23` (`AppDatabase.kt:15`), one entry added to
`MIGRATION_STATEMENTS` (`Migrations.kt:914`), `MIGRATION_22_23_STATEMENTS`:

```sql
CREATE TABLE IF NOT EXISTS contacts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  display_name TEXT, given_name TEXT, family_name TEXT,
  organization TEXT, job_title TEXT,
  photo_uri TEXT, birthday TEXT, notes TEXT,
  name_source TEXT NOT NULL DEFAULT 'none',
  is_self INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS senders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source TEXT NOT NULL, address TEXT NOT NULL,
  match_key TEXT NOT NULL,
  kind TEXT, primary_order INTEGER NOT NULL DEFAULT 0,
  UNIQUE(source, address)
);
CREATE INDEX IF NOT EXISTS senders_match_key ON senders(match_key);

CREATE TABLE IF NOT EXISTS contact_links (
  sender_id INTEGER PRIMARY KEY,
  contact_id INTEGER NOT NULL,
  kind TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS contact_links_contact ON contact_links(contact_id);

ALTER TABLE events ADD COLUMN sender_id INTEGER;
CREATE INDEX IF NOT EXISTS events_sender_id ON events(sender_id);

INSERT INTO senders(source, address, match_key, kind, primary_order)
  SELECT DISTINCT source, title, '', NULL, 0 FROM events WHERE source = 'sms';
UPDATE events SET sender_id =
  (SELECT id FROM senders s WHERE s.source = events.source AND s.address = events.title)
  WHERE source = 'sms';
```

`match_key` is written as `''` here and computed by `ContactBackfill` — the rule
needs the home country, so it cannot be expressed in the migration's SQL. The
backfill then creates one nameless contact per sender, links them, and fills
`match_key`. Room's schema validation compares index sets and defaults, so these
declarations must match the entities exactly (the trap called out at
`EventEntity.kt:17-20`).

## Code changes

**New**
- `data/ContactEntities.kt` — `ContactEntity`, `SenderEntity`, `ContactLinkEntity`.
- `data/ContactDao.kt`, `data/SenderDao.kt`, `data/ContactLinkDao.kt` — narrow,
  one role each (ISP).
- `core/contacts/NumberIdentity.kt` + `CountryCodeIdentity.kt` — §6.
- `data/contacts/NumberLinks.kt` — §7 merge.
- `data/contacts/AndroidContactImporter.kt` — the provider read, moved out of
  `AndroidContactNames.read()`; upserts per §5.
- `data/ContactBackfill.kt` — one-shot: contacts for senders, `match_key`, links.
- `data/work/ContactImportWorker.kt`, `data/work/ContactBackfillWorker.kt`.
- `core/calendar/CalendarSource.kt`, `core/calendar/BirthdaySource.kt` — §8.

**Modified**
- `data/AppDatabase.kt` — `version = 23`, entities, DAO accessors.
- `data/Migrations.kt` — `MIGRATION_22_23_STATEMENTS`, map entry.
- `data/EventEntity.kt` — `sender_id`; `data/Sql.kt` — senders/merge/window queries.
- `data/contacts/ContactNames.kt` — `ContactNames` becomes DB-backed; retire
  `senderMatchKey`/`MATCH_DIGITS` (evidence 3).
- `data/contacts/AndroidContactWriter.kt` + `core/sms/ContactWriter.kt` —
  `create(name, numbers: List<String>, google)`, one raw contact, N phone rows,
  each canonicalised by the rule.
- `core/calendar/CalendarEntry.kt` — `CalendarKind.BIRTHDAY`.
- `data/CalendarRepository.kt` — merge feed + birthday sources; `upcoming()`
  returns the domain `CalendarEntry`, not the entity (today it returns
  `CalendarDateEntity`).
- `data/work/SyncScheduler.kt` — schedule the contact import.

**UI**
- Contact detail: profile fields, N numbers with `kind`, birthday picker.
- Create form: numbers as a list (the `+91` prefix applies per row).
- Month cell: `BIRTHDAY` kind, visually distinct from holidays.

## Test plan

- **Rule** — the three failure modes from evidence 3 as tests: `+91 98765 43210`
  == `9876543210`; `+44 98765 43210` != `+91 98765 43210`; `098…` == `98…`;
  short code / `VM-HDFCBK-S` → `null`.
- **Merge** — idempotent (second run inserts nothing); `pinned` preserved;
  `SUM(msg_count)` equals the pre-merge total; no `events` row changes (compare
  a checksum before/after).
- **Precedence** — import after a user edit leaves `display_name` and
  `name_source` untouched.
- **Birthday projection** — leap day (`--02-29`); unknown year; a window
  crossing 31 Dec; nothing written to `calendar_dates`.
- **Migration** — 22 → 23 opens (schema validation), backfill twice is a no-op,
  and a pre-existing `pinned` conversation keeps its pin.

## Non-goals

- **Re-keying `conversations`** (§4) — a later slice if measured.
- **`contact_data` / full Google parity** (addresses, IMs, relations) — model it
  when a *second* multi-valued field exists, not before.
- **Photo blobs** — `photo_uri` is a path; offline-first, but not a blob store.
- **Two-way provider sync** — the import is one-way for now; the write path stays
  `ContactWriter`.
- **`source` as a user-facing concept** — it stays an internal namespace.

## Rollout

1. Entities + migration + DAOs (no behaviour change; `sender_id` unread).
2. `NumberIdentity` + `ContactBackfill` → senders linked, names still from the
   provider.
3. `CalendarSource` + `BirthdaySource`, birthday picker in the contact UI.
4. `ContactImportWorker`, then retire `AndroidContactNames`.
5. Contact detail + multi-number create.

## Links

- `android/app/src/main/kotlin/com/personalos/app/data/ConversationEntity.kt`
- `android/app/src/main/kotlin/com/personalos/app/data/EventEntity.kt`
- `android/app/src/main/kotlin/com/personalos/app/data/contacts/ContactNames.kt`
- `android/app/src/main/kotlin/com/personalos/app/data/CalendarDateEntity.kt`
- `docs/adrs/0006-sources-unification.md`
