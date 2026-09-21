# ADR 0004: Sources, kinds and the key vocabulary

- **Status:** **Proposed.** Written from a discussion; **not agreed in full**. Supersedes ADR 0003 §14,
  which was written prematurely and recorded as decided things still under discussion.
- **Date:** 2026-09-16
- **Depends on:** ADR 0003 (sources/items/rules, built), ADR 0001 (zero-cost policy)
- **Companion:** `docs/plans/0003-sources-ui-and-rule-builder.md`

---

## Context

The rules engine is built and device-verified: a rule matched a real item and wrote a row into
`item_rules`. What this ADR fixes is the **model around it**:

- **Two feed lists over one table**, plus a third variant — three treatments of one source of truth.
- **"Sources" named a concept the app has one implementation of** (the registry *is* the feeds and
  queries), so a Sources screen showing RSS feeds read as a duplicate. Renamed **Overview**.
- **Weather and FX produce nothing**, so a rule over a temperature or a rate is not unimplemented but
  *impossible* — there is nothing stored to match, and ADR 0003 §8 defines a monitor's history as
  stored items.
- **The builder was permissive where the model is closed**: free text for source, mention kind and field
  name, so a mistyped value produced a rule that silently never matched.
- **The per-source key vocabulary was never written down**, which made the builder impossible to spec
  and left tags and fields conflated.

## Decision (proposed)

### 1. Sources produce; tiles view

A **source** fetches on its own schedule and writes canonical items. A **tile** is a query over the
store plus config, and never fetches. Different taxonomies, not one hierarchy: `Weather` the tile is one
view; the weather *sources* are one per place. Collapsing them puts transport in the view layer and
gives one number two owners. Weather, FX and SMS keep their current homes; they are not gathered into a
single "sources" group.

### 2. Kinds and instances

A source has a **kind**; most kinds have **instances**. Instances are rows in `sources`
(`kind` + `spec_json` + `interval`), which is how the table already works.

| kind | instance | identity |
|---|---|---|
| `rss` | one publisher feed | `rss:<feed id>` (seeded) · `userrss:<source id>` (user) |
| `search` | one query — Topics | `gnews:<slug>` |
| `sms` | the device's messages — exactly one | `sms` |
| `weather` | one place | `weather:<place slug>` |
| `fx` | one pair | `fx:<pair slug>` |

**No `fares` kind.** Fares come through search APIs and are cached in the store, with `price` / `stops`
arriving as keys.

The registry row id (`seed:rss:thehindu-top`) is **not** the identity used in `events.source`
(`rss:thehindu-top`). Existing logic owns that mapping; nothing may assume they are the same string.

---

## 3. The key vocabulary

A rule matches keys. Each key has an origin, and the origin decides whether it is universal or
per-source.

### 3.1 Universal keys — every item, whatever produced it

| key | comes from | cardinality | values |
|---|---|---|---|
| `source` | `events.source` | single | `sms`, `rss:thehindu-top`, `gnews:kolkata`, `weather:kolkata`… |
| `title` | `events.title` | single | free text (for SMS, the sender) |
| `content` | `events.content` | single | free text (for SMS, the body) |
| `date` | `events.timestamp` | single | timestamp |
| `url` | `events.url` | single | nullable; a Google News url is an aggregator redirect |
| `subject` | `item_tags`, active tagger | **multi** | `finance`, `tech`, `travel`, `weather`, `paper`, `festival`, `games` |
| `nature` | `item_tags`, active tagger | **multi** | `incident`, `expense`, `promo`, `personal`, `official` |
| `marker` | `item_tags`, active tagger | **multi** | `news` |
| `place` | `mentions` where `kind = place` | **multi** | `Kolkata`, `Indiranagar`… |
| `party` | `mentions` where `kind = party` | **multi** | `BJP`, `TMC`… |

Two things this replaces:

- The old `text` predicate's `target` (`title` / `content` / `any`) disappears: `title` and `content`
  are simply two keys, and "any" is `any[title ~ X, content ~ X]`.
- The tag keys are **universal, not per-source**, because any item can carry tags — which is precisely
  why an SMS can be `nature=incident`.

### 3.2 Per-source keys — declared by the kind

Additional to the universal set, never instead of it.

| kind | declared key | type | extracted by |
|---|---|---|---|
| `sms` | `sender` | string | `SmsSource` — the address |
| `sms` | `amount` | number | `SmsAmountParser` |
| `weather` | `temp_c`, `feels_like_c`, `humidity`, `wind_kph` | number | the weather producer |
| `fx` | `rate` | number | the fx producer |
| `rss`, `search` | — | | nothing beyond the universal set |

### 3.3 Tags are not fields

- A **tag** is a *classification*: "what is this?" It is inferred, carries a tagger id and confidence,
  and lives in `item_tags`.
- A **field** is an *extracted fact*: a number or string read off the payload, living in `item_fields`.

An SMS can carry `amount = 12480` **and** `nature = expense` at once. They are not alternatives, and a
rule may use both. An earlier draft of this ADR listed SMS's keys as "sender, amount", which implied SMS
sat outside the tag model. It does not.

### 3.4 The taxonomy, as code

Subjects: `finance`, `tech`, `travel`, `weather`, `paper`, `festival`, `games`.
Natures: `incident`, `expense`, `promo`, `personal`, `official`.
Marker: `news`.

`marker` stays its own group rather than folding into natures, because it is expected to grow beyond
`news`. ARCHITECTURE §10.5 additionally lists `announcement` and `maintenance` — these **do not exist
in code**; that is a discrepancy to resolve deliberately, not to fix silently.

---

## 4. Per-source reference

What each source supplies, so the builder and the evaluator can be specified without guessing.

### 4.1 `sms` — the device's messages

- **identity** `sms` · **one instance** · ingested on change (foreground observer) and on a periodic
  background poll (~15 min floor)
- **universal keys it populates**: `source`, `date`, `title` (the sender), `content` (the body), and
  after tagging, `subject` / `nature` / `marker`; after mention extraction, `place` / `party`
- **declared keys**: `sender`, `amount`
- **it can be** `nature = expense`, `promo`, `incident`, `personal`; `subject = finance`… — the full
  taxonomy applies
- **extractors**: `SmsSource` (sender + fields), `SmsAmountParser` (amount), `HeuristicTagger` (tags),
  `MentionExtractor` (places, parties)

### 4.2 `rss` — publisher feeds

- **identity** `rss:<feed id>` for seeded catalog feeds, `userrss:<source id>` for user-added ones
- **universal keys**: `source`, `date`, `title`, `content`, `url`, plus tags and mentions after ingest
  and enrichment
- **declared keys**: none
- **caveat**: a feed's `content` is often the summary; enrichment later fills it. That is the one case
  where a predicate's answer can change after ingest (ADR 0003 §10)

### 4.3 `search` — Topics (Google News today)

- **identity** `gnews:<slug>` — the **query** is the identity, not the publication
- **universal keys**: as `rss`, but two differences that matter:
  - `url` is a `news.google.com` redirect blob, so it is unreliable to match on
  - the outlet is **inside `title`** (`"… - The Hindu"`), because one query returns many publications
- **declared keys**: none
- **consequence**: the same article arriving via a feed and via a query is **two items** with two
  identities. Honest, and the reason the backlog carries a dedupe item
- **naming to fix**: the kind is `search`, the identity prefix is `gnews:`, the UI says "Google News".
  The provider belongs in `spec_json`, so Exa/Tavily later is a new provider and not a new kind

### 4.4 `weather` — one per place *(proposed, not built)*

- **identity** `weather:<place slug>`
- **universal keys**: `source`, `date`; `place` as a **mention** so place handling stays unified;
  `title`/`content` may carry a human summary
- **declared keys**: `temp_c`, `feels_like_c`, `humidity`, `wind_kph`
- **shape**: series. One fetch yields N observations (per place, per valid time)
- **identity rule**: **(source, valid time)** — never content-derived, or a re-poll duplicates
- **enables**: `temp_c > 40` as a rule today, and a crossing monitor once slice 5 lands

### 4.5 `fx` — one per pair *(proposed, not built)*

- **identity** `fx:<pair slug>` (e.g. `fx:usd-inr`)
- **declared keys**: `rate`
- **shape**: series, one observation per fetch
- `FxProvider`'s own doc already said currency and fares would share one shape — which is why fares
  need no separate kind

### 4.6 `device` — local state *(proposed, not built)*

- **identity** `device:<signal>` — `device:battery`, `device:charging`, `device:network`
- **declared keys**: `battery_pct`, `charging`, `network`
- **shape**: pushed, not polled — it fires when the device changes, like the SMS observer
- **why it belongs**: it is the category that makes this app different from a feed reader —
  `charging = true` or a place in range are rules nothing else can express

### 4.7 Not sources

`places` is a bundled gazetteer asset; `parties` is a syncer writing rows, not items; `glance` is a
summary view over events; `pinned` is bookmarks (`events.bookmarked`); `nowPlaying` describes a radio
player. None of them produces items, so none is a source.

### 4.8 Legacy columns

`events` still carries `type`, `category`, `entities` and `location`. They predate tags and mentions:
`category` was to become a tag row, and `entities` / `location` are superseded by `mentions`. The key
vocabulary above uses the newer tables; nothing should be built on the legacy columns.

---

## 5. How the UI lists sources for selection

Two levels, kinds → instances:

- **Level 1 — the kinds that exist.** `SMS · Feeds · Topics · Weather · FX`. A kind appears only once it
  has an instance, so the list can never offer a source that produces nothing.
- **Level 2 — the instances of that kind.** Feeds → The Hindu, Mint Money, RBI Press Releases…;
  Topics → Bangalore, Kolkata…; Weather → the places; FX → the pairs; SMS → the device (one row).
- **Selection is multi-kind and multi-instance.** **Empty means every source**, which is what makes a
  cross-source rule possible.
- **Label versus value.** The UI shows the instance's *name*; the rule stores its *identity*
  (`rss:thehindu-top`). They differ deliberately: names change, and a rule must survive a rename.
- **The key list follows the selection.** Universal, tag and mention keys are always offered. Declared
  keys appear only for the kinds selected — SMS adds `sender` and `amount`; adding Weather adds
  `temp_c`…; Feeds alone adds nothing beyond the universal set. That is what stops the builder offering
  a key no source can supply, which is how a rule that never fires gets authored.

## 6. What the unified schema buys

Every producer's output lands in the same four tables — `events`, `item_tags`, `mentions`,
`item_fields`. Therefore:

- **one matcher** — the evaluator knows nothing of transports; an SMS, an article, a temperature and a
  rate are the same shape;
- **one builder** — the row is always key / operator / value; a new kind adds keys, not a control;
- **one store, one owner** — the number lives in the item, not in a tile's cache, which is exactly why
  weather and FX must write events to be matchable at all;
- **N sources × M rules without N×M code** — adding a source is a row plus a fetcher; adding a rule is a
  row; neither touches the other.

That last line is the entire argument for the schema. Without it, every source × every rule is a
bespoke integration.

## 7. A rule is interest and action, not detection

Tagging decides **what an item is**; a rule decides **whether you care and what to do**. Tagging is
system-authored, one classification per item, in `item_tags` with a tagger id and confidence. Rules are
user-authored, many per item, each with an action. A rule over a tag is legitimate and cheap — the
tagger did the detecting, and the rule contributes the **action**: an alert, an emphasis, a tab.

What a rule can express that a tag cannot: source scoping, numeric thresholds, cross-key AND, series
windows, and text patterns for whatever the vocabulary has not caught.

**The vocabulary is the shared foundation.** If `bandh` is not a tag, no rule can match it by tag —
which is why the seeded rule text-matches `bandh|strike`. That is a regex patching a vocabulary hole,
per rule. Indian-context incident terms (`bandh`, `hartal`, `flood`, `IMD`, `red alert`) belong in the
vocabulary, where they become keys for every rule at once.

## 8. One builder shape: key, operator, value *(proposed)*

Instead of predicate *types*, every match is `key`, `operator`, `value`, with the key list from §5.
**Caveat:** this changes the shape of `condition_json`, so it needs a versioned parse or a migration. It
is a language change, not a UI change.

## 9. Navigation groups

```
Radar & Monitoring   Radar · Alerts · Watchers · Messages
Feeds                Topics · RSS · Social
Money & Travel       M&M · Travel · Cards · Wallet · Places
Daily                News · Radio · Weather · Notes
App                  Settings · Account · Home
```

`Sources` (the screen) is renamed **Overview**, its grid entry removed, the screen kept — all sources
with counts and a sync action is useful and has exactly one entry point.

---

## Open questions

1. **Do the Weather and M&M tiles read the store**, or keep their provider cache? One owner versus
   rewiring cost.
2. **Does the condition language move to key/op/value now**, or keep today's predicate types until the
   builder needs it? It is a `condition_json` shape change.
3. **Are per-kind declared keys built now**, or is the global `FieldNames.SUPPLIED` grown until a second
   kind with typed keys exists?
4. **Delivery.** No notification code exists anywhere, so a match writes a row and nothing else. The
   IMD heavy-rain example fails on delivery, not on detection.
