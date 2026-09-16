# ADR 0003: Sources, Items and Rules — interfaces, not mechanisms

- **Status:** Accepted (design). Supersedes the model in ADR 0002. Slice 1 — the `sources`
  rename, `rules` and `item_rules`, schema v11 — landed in `43517af`; everything after the tables
  is not yet implemented.
- **Date:** 2026-09-15 (all previously open questions resolved — see the end)
- **Deciders:** Owner (single user)
- **Scope:** the producer interface, the canonical item, source specs, the predicate
  language, delivery vs surfacing, and how rules/matches are stored. Not UI, not
  tile layout.

---

## Context

ADR 0002 shipped a table called `rules` that contains no rules. Verified against
the live schema: 13 rows, all seeded, with `kind` (`rss` | `search`) and
`spec_json` (fetch parameters). That is a **source registry**, not a rule engine —
nothing in it holds a condition, an action, delivery or position.

Meanwhile tiles filter by hardcoded values (News = tag `news`, M&M = tag
`finance`, Radar's tabs map to stored category strings), so there is no
user-writable way to decide what lands where.

Three forces shaped this design:

- **SMS is the majority of the store** (2,492 of ~4,080 items in the last device
  pull). It has no URL, no HTTP fetch, and its own identity (a provider row id).
  Any model that assumes a web article is wrong for most of our data.
- **Producers are multiplying**: RSS/Atom, Google News, SMS, email, Mastodon,
  Bluesky, scholarly APIs (OpenAlex), search APIs (Exa/Tavily), RapidAPI vendors,
  headless scrapers, and eventually our own Worker.
- **A producer may be stateful and non-deterministic.** A Skyscanner-style source
  needs a live session (cookies from the WebView), parses HTML, and may use an LLM
  to extract values. That is the producer's private business.

## Decision

### 1. Producers are an interface. Internals are opaque.

A producer yields **canonical items**. How it fetched them — HTTP, a content
provider, IMAP, a headless browser with a cookie jar, a regex, a small trained
model or an LLM — is invisible above the boundary, and so is whether it is
deterministic.

This is what makes the model work: **values are frozen into items at ingest.** A
non-deterministic producer re-fetching the same source cannot rewrite history; it
only adds or dedupes. Non-determinism is absorbed at the edge, so everything
above is deterministic over the store.

Two roles exist, and each output declares which it satisfies:

| role | contract | example |
|---|---|---|
| **producer** | emits canonical items | RSS feed, SMS reader, a Worker emitting JSON |
| **enricher** | fills payload for an item someone else produced | `ArticleEnricher` |

A single deployment may do both, but the outputs must be distinguishable — else
the app cannot tell whether a feed *is* the data or *supplements* it.

### 2. Shape families

Grouped by shape, because that is what the schema must carry:

| shape | who | paging |
|---|---|---|
| item feed | RSS/Atom, JSON Feed, gnews | none, or `next_url` |
| paged collection | OpenAlex, Exa, Tavily, Mastodon, Bluesky, RapidAPI | cursor / offset / page |
| message stream | SMS, email | high-water mark / UID |
| **document fetch** | headless scraper, PDF grabber | *not a source* — enrichment |
| canonical producer | our Worker | ours to define (`map` empty) |
| **series** | fares, prices, any repeating measurement | timestamp / object key |

### 3. The canonical item

JSON Feed 1.1 field names where they exist, so a published spec does the
documenting; readers of that spec must ignore unknown fields, which is the
additive-change guarantee we want.

- **identity** — `id`, from the source when it declares one (a DOI beats a URL; a
  Mastodon post URI is stable; SMS uses its provider row id; email its UID), else
  `sha256(canonical_key).take(16)` with a per-transport key. **Never derived from
  content.** The pre-hash key is stored for debugging.
- **core** — `source`, `title`, `date`, and one of `content_text` / `content_html`.
- **optional** — `url`, `summary`, `authors`, `image`, `attachments` (a scraped PDF
  lands here), `language`, `tags` (declared by the source).
- **`fields`** — kind-specific typed extras: `amount`, `sender`, `doi`,
  `citation_count`, `price`, `stops`, `handle`, `thread_id`. Persisted as rows in
  **`item_fields`** (§13), not as a JSON blob on `events`.

Identity and source identity are mechanism-independent. Today's
`rss:thehindu-top` bakes transport into identity: move that feed to a scraper and
every rule keyed on it stops matching. The mechanism belongs in the spec; the
identity belongs to the row.

**Writes are `IGNORE` on the dedupe key, so first write wins.** Right for a
snapshot or an article; wrong for a value a producer later *revises*. Whether a
kind updates on revision is a per-kind decision, not a global one.

### 4. Capabilities are declared per kind

So a blank is interpretable:

```
rss      → title, url, date, summary(usually), author(sometimes)
gnews    → title, url(redirect), date, outlet          · no full text
sms      → title(sender), content(body), date          · no url
email    → title(subject), content, date, thread, attachments
openalex → title, doi, authors, abstract, venue         · no readable url
worker   → whatever it emits
```

`summary: null` from `rss` means "the feed shipped none" (enrich if there's a url);
from `sms` it means "this kind has no such field" — never worth enriching.

### 5. Source spec: fetch, page, map

`kind` is data with one adapter per kind. Secrets are referenced by name, never
stored.

```jsonc
{ "kind": "rss",   "fetch": {"url": "https://indianexpress.com/feed/"} }

{ "kind": "gnews", "fetch": {"query": "West Bengal", "hl": "en-IN", "gl": "IN", "ceid": "IN:en"} }

{ "kind": "json",  "fetch": {"url": "https://api.example.com/news?q={query}",
                             "auth": {"keyRef": "RAPID_API_KEY", "in": "header", "name": "X-RapidAPI-Key"}},
  "page":  {"style": "cursor", "param": "cursor", "in": "$.meta.next_cursor"},
  "items": "$.data",
  "map":   {"id": "$.uuid", "title": "$.headline", "url": "$.link",
            "date": "$.published_at", "summary": "$.excerpt",
            "fields": {"amount": "$.amount"}} }

{ "kind": "worker", "fetch": {"url": "https://…/fares.json"} }   // already canonical: no map
```

- **`page`** — `none` | `next_url` | `cursor` | `offset` | `highwater`. Without it a
  source can only ever read page one.
- **`map`** — paths plus a *small named set of transforms* (`inverted_index` for
  OpenAlex's abstract, `dateparse`, `concat_authors`). Named and data-driven, so a
  new source never becomes a new branch. Optional: a canonical producer needs none.
- **`session`** — a spec may declare a stateful transport (`webview:<profile>`, or a
  cookie ref). That also decides where it can run; cookies live on the phone, so a
  Worker would need the session replicated.

### 6. Predicates — two families

**Item predicates** read one item:

| predicate | reads | example |
|---|---|---|
| `subject` | tag in the subject set | `subject is games` |
| `nature` | tag in the nature set | `nature is incident` |
| `marker` | the `news` tag | rarely useful |
| `mention` | a mention row | `place is Kolkata`, `party is BJP` |
| `source` | the source row | `source is The Hindu` |
| `field` | a typed extra | `amount > 10000` |
| `text` | stored text, with a target | `text(any) ~ "bandh"` |

Composed with `all` / `any`.

**Series predicates** read a *window* of a series source, not one item:
`crossing`, `delta`, `min`/`max over N`. This is what "a fare drops below ₹30,000"
actually is, and the mockups' flagship recipe cannot be expressed with item
predicates alone.

**Frozen vs enrichment-sensitive.** Tags, mentions, source and fields are frozen at
ingest. Text can *grow*: a feed ships a headline, the enricher fills `content`
later. So a text predicate's answer can change after the fact — see §8.

### 7. Rules never fetch

**Sources fetch; rules evaluate.** A source polls on its own schedule (default
interval, user override) straight into the store. Rules are pure evaluators over
what is already stored.

Consequences, all of them simplifications:

- Multiple rules over one source cost nothing extra — no N× fetch.
- No rule can trigger HTTP, so no user-authored rule can hammer a host or trip a
  bot gate.
- "At most once per day" in the mockup is a **delivery** concern (don't repeat the
  alert), not a fetch-rate concern.

### 8. Monitors and history

A monitor is a rule whose condition contains a series predicate. Its **history is
our stored items** — the price points are `item_fields` rows joined to `events`, and
the series is those items ordered by date. So monitors are offline, deterministic
and replayable, which is what lets the dry run replay a window exactly.

Three consequences to design for, not discover:

1. **Gaps are permanent and must not read as changes.** Upstream retention plus a
   missed sync means observations never ingested are gone. A monitor must tolerate
   gaps explicitly (require a value within N of the boundary, or refuse to fire
   across a recorded gap) rather than treating a hole as a drop.
2. **A monitor's window can never exceed the producer's retention.** Otherwise it
   silently evaluates over a partial series.
3. **Growth.** Prices × series × time is the one place where "we never prune"
   becomes a problem. The window query wants an index on the series key, and
   eventually a materialised rolling window instead of scanning `events`.

### 9. Delivery and surfacing are different axes

- **Delivery** is the *interruption*: one target, `push` or nothing. It is a
  side-effect, not a location.
- **Surfacing** is a read-time projection. Radar is this: a wall is a query, not an
  inbox.

So Radar needs no rule and no target. It is already an aggregate read over
`events` joined to tags and mentions, across SMS, feeds and everything else; its
tabs are filters on that read. Rules contribute two things, neither of which is a
destination:

- **Emphasis** — `position`/pin, so a match floats above the stack rather than
  sitting at its timestamp.
- **Membership** — a tab may be *defined as* "matches of rules X" instead of "tag
  Y". Because matches are materialised, that is an index lookup at the same cost as
  the tag filter it replaces.

**A match is stored once and visible everywhere the views ask for it.** Delivery
doesn't move an item — it only decides whether you are *told*. So "fire to Alerts
and show in Radar" is one stored item, one alert, and Radar reading the store.

**Three consumers, and rules replace none of them:**

| consumer | driven by |
|---|---|
| **Alerts** | rules (new) |
| **tiles** (News, M&M, Weather…) | their own tags, unchanged |
| **Radar tabs** (All, Incidents, Money, Travel, Bills) | fixed list today, expandable, possibly user-created later |

### 10. Re-evaluation policy

Bounded and predictable, with no full re-scans:

- **On ingest** — evaluate new items only.
- **On rule edit** — a dry run over history that persists nothing.
- **On enrichment** — re-evaluate **only** rules whose condition contains a text
  predicate, scoped to the items actually enriched.

### 11. Enrichment is one owner with a declared state

`content` otherwise has two writers: the source's summary, then the enricher. An
item therefore declares how its text got there — `publisher` | `extracted` |
absent — and the device enriches only when absent and the kind says it is
worthwhile. `enriched_at` keeps its three states (null = never tried, 0 = tried and
failed, >0 = done) so a gated host is not retried forever, and a second enricher
(a Worker) can be skipped rather than double-writing.

### 12. Query structure

Coarse filter on indexed columns in SQL (tag, source, date window, mention) →
evaluate text and comparisons in the pure evaluator. Regex cannot be indexed, and
interpolating a user pattern into SQL is worse than slow. At ~4k items a scan is
milliseconds: **no FTS yet**; revisit at ~100k. Because the condition language and
the search language are the same evaluator, structured search comes free — the
mockups already assert this ("Equivalent query · same rule in every builder").

Two hard edges, both already met in this codebase: **ReDoS** (bound the input
length and/or match time — a user pattern must not stall ingest) and **Unicode**
(`\b` is ASCII-only so it never fires around Devanagari; `(?U)` crashes Android
while `(?u)` works — the boundary form from `PlaceIndex.wordPattern` applies).

### 13. Tables

`rules` (today's table) is **renamed to `sources`**; data unchanged, a plain
`ALTER TABLE … RENAME TO`. `rules` becomes the real thing, and matches are
materialised like tags:

```sql
CREATE TABLE sources (
    id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL,
    spec_json TEXT NOT NULL, seeded INTEGER NOT NULL, enabled INTEGER NOT NULL,
    created_at INTEGER NOT NULL, updated_at INTEGER, interval_sec INTEGER,
    PRIMARY KEY(id)
);

CREATE TABLE rules (
    id TEXT NOT NULL, name TEXT NOT NULL,
    enabled INTEGER NOT NULL, seeded INTEGER NOT NULL,
    condition_json TEXT NOT NULL, action_json TEXT NOT NULL,
    position INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER,
    PRIMARY KEY(id)
);

CREATE TABLE item_rules (
    item_id TEXT NOT NULL,   -- events.ulid
    rule_id TEXT NOT NULL,
    matched_at INTEGER NOT NULL,
    PRIMARY KEY(item_id, rule_id)
);

CREATE TABLE item_fields (
    item_id TEXT NOT NULL,   -- events.ulid
    name TEXT NOT NULL,      -- "amount", "sender", "doi", "price", ...
    value_num REAL,          -- exactly one of the three is set
    value_text TEXT,
    value_flag INTEGER,
    PRIMARY KEY(item_id, name)
);
CREATE INDEX index_item_fields_name_item_id ON item_fields (name, item_id);
CREATE INDEX index_item_fields_item_id ON item_fields (item_id);
```

**Why `item_fields` is a table and not a JSON column on `events`.** A typed extra is
sparse and kind-specific, which makes a JSON blob on the item the tempting shape —
and it is wrong here. §8's monitors need a *series key* that can be indexed, and a
JSON column cannot be indexed by key without expression indices that SQLite would
still scan. So fields are materialised like tags and matches: one row per
(item, name), indexed by `(name, item_id)` for window queries and by `item_id` for
reading one item's extras. The three typed columns mirror the language's
`FieldValue` (`Num` / `Str` / `Flag`) exactly, so no widening or parsing is needed on
either side.

**A defect this ADR carried until now:** §3 defined `fields` and §8 asserted that
series points are "rows in `events` with their `fields`", but no storage for them was
ever specified — so nothing could supply a `field` predicate, and a rule using one
would have evaluated to false forever while looking like a broken engine. The
omission is fixed here; the storage lands as its own schema slice.

### 14. Kinds, instances and declared keys (decided 2026-09-16)

A source has a **kind**, and most kinds have **instances**:

| kind | instance | declares keys |
|---|---|---|
| `rss` | one feed — The Hindu, Mint Money, … | — |
| `gnews` | one query — "Kolkata" | — |
| `sms` | the device's messages | `sender`, `amount` |
| `weather` | one place — Kolkata | `temp_c`, `feels_like_c`, `humidity`, `wind_kph` |
| `fx` | one pair — USD/INR | `rate` |
| `fares` (later) | one route | `price`, `stops` |

Instances are rows in `sources` with `kind` + `spec_json` (a url, a place, a pair) +
an interval — which is already how the table works. So the authoring UI is
**two-level: pick the kind, then the instance**, and a rule's source filter may name
several instances across several kinds, or none at all (empty = every source, which
is what makes a cross-source rule possible).

**A kind declares its keys.** This is what makes the rule builder honest rather than
permissive: the Match-when field picker offers the keys of the kinds actually
selected, so `temp_c` appears because a weather source is in play, and a field no
selected kind supplies simply isn't offered. Today `FieldNames.SUPPLIED` is a global
`{sender, amount}`, which is why the builder cannot suggest a temperature — it has no
idea weather exists.

**`HistoryProvider` is not a separate concept.** It is the **series** shape from §2
("fares, prices, any repeating measurement"). Its observations are canonical items:
`source` = the instance, `timestamp` = when it is valid, keys in `item_fields` (v12
exists for exactly this), and a place as a **mention** so place handling stays
unified. A monitor's window is then just stored rows, as §8 already requires.

**Services (tiles) are not providers, and must not become them.** A tile is a view
plus config (§ "one fact, one owner"), and a tile can aggregate several sources —
News shows many feeds, Radar shows everything — whereas a provider produces one
stream. Collapsing them would break aggregation and put transport concerns in the
view layer.

**But every service whose numbers come from outside must be backed by a source**, so
the number lands in the store rather than in a provider cache the tile reads. Without
that, a `temp > 40` rule is not unimplemented but *impossible*, and no monitor can
ever watch a rate — there is nothing stored to match or trend.

Consequence for Weather and M&M: their providers currently render a cache and write
no events. They become series sources — one instance per place, one per pair — and
the tiles then read the store. That rewiring is the real cost, and the empty state
matters: a tile whose source has not fetched yet must say so rather than show nothing.

## Consequences
**Positive**

- Adding a producer is a spec plus one adapter; adding an interest is a row.
  Neither is a screen change — the Open/Closed test in `CODE-DESIGN-GUIDELINES.md`.
- The engine never learns whether a source was stateful, scraped or
  model-assisted, because non-determinism is absorbed at ingest.
- Rules being pure evaluators means they cannot fetch, rate-limit, or be
  responsible for network behaviour at all.
- One evaluator serves rules, structured search and the dry run.
- Radar and tiles stay views over one store, so nothing can drift.

**Negative / accepted**

- The rename touches `RuleRepository`, `RuleSeeder`, `FeedIngestor`, the RSS screen
  and label resolution — mechanical, but a migration on a live DB.
- Text predicates make their rules enrichment-sensitive; that recompute is real
  work and must stay scoped.
- Hash ids are opaque in logs; the stored key column is the mitigation.
- `map` transforms are a small language, and small languages grow. The named set is
  deliberately minimal and must stay so.
- Monitors add the first real pressure on "we never prune", and retention is a
  constraint we do not control.

## Alternatives considered

- **Keep `rules` as sources and name the engine differently.** Rejected: two things
  called rules in a schema people read.
- **URL as `id`.** Rejected: not stable across our aggregation — Google News
  redirect blobs and tracking parameters both observed.
- **Assume HTTP.** Rejected: SMS is the majority of the store and has no URL.
- **Put provenance/confidence in the item contract.** Rejected as a requirement:
  that is the producer's business. Fields may carry it; the engine must not need it.
- **Rules as fetchers.** Rejected: it makes every rule a network actor, multiplies
  fetches per source, and puts bot-gate risk in user-authored rows.
- **Delivery as a location ("deliver to Radar").** Rejected: Radar is a query, not
  a destination. Conflating the two forced two writers for "what Radar shows".
- **Evaluate rules at read time.** Rejected: tile queries would carry rule logic
  and nothing could be indexed.
- **Require a session or determinism at the interface.** Rejected: internals are
  opaque, and a source that needs cookies is still just a source.

## Resolved questions

1. **Series predicates** → a separate **monitor with history**; the history
   provider is a *source* that emits observations, and the monitor reads a window
   of our stored items (§8).
2. **Rules vs tile filters** → **no replacement.** Alerts are rule-driven; tiles and
   Radar tabs keep their own filters (§9).
3. **Delivery semantics** → **delivery is the interruption, not a location**; one
   target (push/none), with Radar surfacing as a read-time query, plus rule-driven
   *emphasis* and *membership* (§9).
4. **Cursor ownership** → the cursor **lives with the data**: a Worker publishes
   snapshots to object storage and the app reads the latest, the marker being the
   last ingested key. How far back to ask is bounded by retention (§8).
5. **Rate limiting** → a **delivery** concern, because **rules never fetch** (§7).
6. **Re-evaluation trigger** → ingest evaluates new items; rule edit runs a
   persisting-nothing dry run; enrichment re-evaluates only text-predicate rules,
   scoped to the enriched items (§10).
