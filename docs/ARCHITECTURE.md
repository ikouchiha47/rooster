# Architecture

Status: **for review**. `[RESEARCH]` marks something unverified or not yet chosen.

Related: `adrs/0001-source-layer-and-zero-cost-policy.md`, `plans/0001-data-sources-places-and-alerts.md`,
`research/india-feed-catalog.md`, `SMS-CLASSIFICATION.md`, `DESIGN-GUIDELINES.md`, `KOTLIN-BEST-PRACTICES.md`.

---

## 1. Core model

**Sources ingest once. Everything becomes a tagged Event. Tiles are views over that store.**

```
Source (adapter)  ->  Event (multi-tagged)  ->  Store (Room)  ->  Views (tiles / tabs)
```

- **One fetch path per source type:** SMS, email, RSS/Atom, JSON APIs (weather, FX, status), WebView-session sites.
- **Multi-valued tags, in three groups** (§10.5): a **subject** (what it is about - `finance`, `tech`, `travel`; declared by a specialist source, otherwise inferred per item), a **nature** (what kind of thing it is - `incident`, `announcement`; inferred by the tagger, ≤3-4 per item), and the structural marker `news`. Classification is never exclusive: a flood is `news` + `weather` + `incident`, and a Mint markets story is `news` + `finance`.
- **Tiles compose.** Each tile is a query (tags + place + time) plus its own config. Tiles never fetch.
- **Shared shell.** Search + a context action bar sit on most pages; the actions change per tile. Each tile owns a **config model**; the global Settings page is the aggregate of all tile configs (one table, one store).
- **Sources fetch; rules never do.** A source polls on its own interval straight into the store; a rule is a pure evaluator over what is already stored, so N rules on one source cost no extra fetch. Delivery is the *interruption* (a push, or nothing); *surfacing* is a read-time projection, so Radar is a query over the store rather than a destination. Rules contribute emphasis (`position`) and membership (a tab defined as "matches of rules X"), and a match is written once into `item_rules` and read wherever a view asks for it. A rule is therefore not tile-scoped. See ADR 0003.
- **Places are global.** Home places + a radius slider ∪ explicitly picked states/places. Every event tries to carry a place (gazetteer match; feed default as fallback).
- **Bookmarks are global.** Any event can be pinned/saved and is preserved for export regardless of retention.

---

## 2. Data model (target)

| Table | Holds |
|---|---|
| `events` | source, type, **tags[]**, place_id, timestamp, title, content, entities[], url, bookmarked |
| `places` | bundled gazetteer (GeoNames IN + LGD): name, aliases, parent chain, population, lat/lon, bbox |
| `home_places` | places the user cares about |
| `locality_prefs` | radius km, explicitly picked state/place ids |
| `sources` | your subscriptions — was the misnamed `rules` table before schema v11: `kind` (`rss`/`search`/…), `spec_json` (fetch, page, map), `seeded`, `enabled`, `interval_sec` |
| `rules` | a condition, not a subscription: `condition_json`, `action_json`, `position`, `seeded`, `enabled` — a pure evaluator over stored items (ADR 0003) |
| `item_rules` | materialised matches, like `item_tags`: `(item_id, rule_id, matched_at)`, written once at ingest and re-read wherever a view asks |
| `watchers` | tracked values: kind (fx/fare/status/…), params, target, alert state |
| `settings` | per-tile config blobs (single store; Settings page aggregates) |
| `retention` | per-tag retention windows |

**Fix required:** current unique index is `(source, type, timestamp, title)` and `type` was renamed (`news` → `feed`), which duplicated every article. Under §11 this is replaced: `category` becomes a tag row, `type` is dropped, and dedupe moves to a computed `dedupe_key`. Full schema in §11.

---

## 3. Source inventory

| Source | Status | Notes |
|---|---|---|
| SMS | **live** | `content://sms`, `READ_SMS`, 2,480 rows, incremental high-water mark |
| RSS/Atom feeds | **live** | 5 news + 2 status seeds; cached 1h; entity-sanitising parser |
| Weather | **live** | Open-Meteo, 7 days cached per location, refreshes 6h |
| FX | **live** | Frankfurter/ECB, cached 6h |
| Email | planned | IMAP + app password, or email-to-feed. `[RESEARCH]` which path |
| Status pages | planned | Statuspage `/api/v2/incidents/unresolved.json`. AWS is not Statuspage (UTF-16 `data.json`) |
| Downdetector | planned, **last** | Crowdsourced; Cloudflare JS challenge; needs WebView session + cookie replay `[RESEARCH]` |
| Search APIs (Exa/Tavily/DDG) | planned | Discovery only, never ingestion; quota-metered |
| Radio | live (sample) | radio-browser.info adapter not built |
| Fares / flights | **blocked** | No free live API (Amadeus decommissioned, Kiwi invite-only, Duffel test-only) `[RESEARCH]` |
| Stocks / market | `[RESEARCH]` | Free Indian equity is EOD bhavcopy + ~15-min-delayed Yahoo only |

---

## 4. Service tiles (12)

`Shows` = what the screen renders · `Actions` = context bar · `Settings` = per-tile config

### 4.1 Radar
Unified timeline. `Shows`: curated default (small, balanced), then tabs. `Actions`: Search, Sync, Add Rule. `Settings`: dedupe on/off, curated cap, retention.
`[RESEARCH]` the curation rule (round-robin vs priority) and the default cap.

### 4.2 News
Purpose: **everything editorial.** News is the widest tag - most items land here and several tiles read from it.
`Shows`: news ranked **nearest-first**, as a stack of location tiers:

```
major national events   <- BRICS, elections, bandhs: pinned above the stack
city/town               <- home places
state                   <- parent of each home place
surrounding states      <- sampled, capped, with [more]
country                 <- the rest
```

Within each tier: **rule matches first**, then recency. Each row shows source, time, place chip, tags.
`[more]` on a tier opens that tier as its own filtered view (never expands endlessly in place).
`Actions`: Search, Add Feed, Add Rule, Settings.
`Settings`: selected locations, global radius (km), surrounding-state sample size, tier caps, feed list.
Sources: `awesome-rss-feeds` (India broad + a few interests, OPML-seeded) and Hushread's curated categories.

**India OPML pulled and checked** - `countries/with_category/India.opml` holds **36 feeds**. Cross-checked against `research/india-feed-catalog.md` and the seeded `FeedCatalog`:

| Already covered | Note |
|---|---|
| The Hindu, Times of India, Indian Express, NDTV (FeedBurner), Business Standard, Economic Times, Firstpost, The Hindu BusinessLine, India Today, News18, Deccan Chronicle, Divya Bhaskar (Gujarati) | present in catalog and/or seeds |

| Genuinely new | Why it matters |
|---|---|
| **SEBI** `sebi.gov.in/sebirss.xml` | regulatory/orders - **M&M** |
| **Financial Express** `financialexpress.com/feed/` | markets - **M&M** |
| **Amar Ujala, Navbharat Times, Patrika, Jansatta, Live Hindustan, Dainik Bhaskar** | Hindi national - language coverage (catalog had only Aaj Tak + News18 Hindi) |
| **Maharashtra Times, Loksatta, News18 Lokmat** | Marathi |
| **Gujarat Samachar** | Gujarati |
| Swarajya, TechGenyz, Outlook India, Free Press Journal, Oneindia, Scroll.in, ThePrint, OpIndia | opt-in, not default |

**The 8, all present in the OPML - now actually tested (curl, browser UA, 2026-09-14):**

| Feed | OPML URL | Result |
|---|---|---|
| India \| The Guardian | `theguardian.com/world/india/rss` | **200 xml** - usable, new |
| Free Press Journal | `freepressjournal.in/stories.rss` | **200 xml** (599 KB) - usable, new |
| Oneindia | `oneindia.com/rss/news-fb.xml` | **200 xml** - usable, new |
| SEBI | `sebi.gov.in/sebirss.xml` | **200 xml** - usable, new, M&M |
| TechGenyz | `feeds.feedburner.com/techgenyz` | **200 xml** - usable, new, tag `tech` |
| Scroll.in | `feeds.feedburner.com/ScrollinArticles.rss` | **200 xml** (296 KB) - the FeedBurner mirror **bypasses** the HTML-only verdict |
| Moneycontrol | `moneycontrol.com/rss/latestnews.xml` | **200 `application/xml`** - works **with and without** a browser UA. The catalog's "Akamai 403" is **wrong** |
| ThePrint | `theprint.in/feed/` | 200 but **`text/html`** - Cloudflare challenge, confirmed unusable by plain fetch |
| Financial Express | `financialexpress.com/feed/` | **410 Gone** (`wp_die`). `/rss/` also 410; `/market/feed/` and `/business/feed/` return HTML. **No usable feed** at the OPML URL |

**Two catalog corrections required:**
1. **Moneycontrol is not blocked.** It returns valid RSS on the OPML URL with no UA spoofing. The earlier 403 was transient or UA/timing-dependent - re-test before trusting any "permanently blocked" verdict.
2. **Scroll.in has a working FeedBurner mirror** despite `scroll.in/feed` being HTML-only.

**Still genuinely blocked:** ThePrint (JS challenge). **Still unfound:** Financial Express.

`[RESEARCH]` The general lesson: third-party mirrors (FeedBurner) frequently outlive the publisher's own feed path, so re-test the OPML's alternate URLs against every "blocked / HTML-only" verdict in the catalog.
`[RESEARCH]` place extraction quality for small towns; how to rank "major national events".

### 4.3 M&M (Money & Market) — was Money
`Shows`: tracked conversions (from→to), market/price movements, and the news that explains them below. `Actions`: Search, Add Pair, Add Rule, Settings. `Settings`: tracked pairs, watched instruments.
`[RESEARCH]` free market data (EOD bhavcopy + delayed Yahoo); later AI-derived "what might happen at market level".
`[RESEARCH]` whether news-tagged market items can be attributed to a specific instrument.

### 4.4 Expenses
`Shows`: spend derived from **SMS — including personal messages that name a spend** — grouped (rent, groceries, utilities, transport), plus commodity/price-increase news. `Actions`: Search, Add Category, Add Rule, Settings. `Settings`: categories, budgets/quotas, source tags.
`[RESEARCH]` receipt-image import (share → parse UPI id / merchant / amount); keeping spend accurate when the paying phone differs from this device.

### 4.5 Travel
`Shows`: travel-tagged news (floods, bandhs, disruptions), festival/event dates (Sunburn, Burning Man, Lollapalooza, Tomorrowland, Ziro, Northern Lights), off-season windows. `Actions`: Search, Add Event, Add Rule, Settings. `Settings`: tracked festivals, off-season data, home airports.
`[RESEARCH]` off-season data source (manual JSON first); Exa/Tavily once at year start + near the month, then cached; designing adapters so businesses can supply APIs without exposing keys (e.g. Cloudflare Workers).

### 4.6 Weather
`Shows`:
- **Current location**, a wide 16:9 row, resolved from location services (GPS / network / last-known - whatever is fair).
- **Other places** as square tiles; tapping one opens a 7-day forecast detail screen. The small title on each tile carries **its own sync**.
- **Weather-tagged news below** (floods, earthquakes, cyclones) as a timeline - Weather **composes from the shared store** via the `weather` tag; it does not fetch news itself.

`Sync` exists at three independent points: the root header (below search), each square tile, and the detail screen.
`Actions`: Search, Add Location, Add Rule, Sync, Settings.
`Settings`: locations list (search + add, persisted) - added once, the report stays.
`[RESEARCH]` GPS/network location handling; whether forecast depth beyond 7 days is needed.

### 4.7 Watchers (Alerts + Watchers merged)
`Shows`: every rule across all services in one place, fired history, and **API quota usage** (Exa/Tavily/DDG). Cron-style watchers included. `Actions`: Search, New watcher, Sync, Settings.
`[RESEARCH]` quota model per provider; cron scheduling inside WorkManager.

### 4.8 RSS (Topics merged in)
`Shows`: source status (how many responding), each source with last-updated, recent items, rule-matched items below. `Actions`: Search, Add Source, Sync (per source + global), Settings. `Settings`: sources, retention, bookmarks.
`[RESEARCH]` curated catalog: `plenaryapp/awesome-rss-feeds` (by country and by interest) and Hushread's pre-curated categories — need our own grouping; India broad, a few interests, other countries opt-in.

### 4.9 Social
`Shows`: Bluesky / Mastodon `[RESEARCH]` how to connect (AT Protocol public reads are keyless; Mastodon per-instance).
`Actions`: Search, Add Account, Add Rule.

### 4.10 Radio
`Shows`: stations, now playing, equaliser. `Actions`: Search, Add Station, Sync. Source: radio-browser.info (keyless).

### 4.11 Settings
Aggregates every tile's config from the single `settings` table: Places (home + radius + picked), Sources, Watchers/rules, Notifications (quiet hours), Retention, Data (export/clear).

### 4.12 More
Spare tiles / future (Wallet, Jobs, Cards, Account).

### 4.13 Papers
`Shows`: new papers for the topics / fields / journals you follow, with the abstract inline; the PDF opens externally.
`Actions`: Search, Add Topic, Sync, Settings. `Settings`: followed topics/fields/journals/authors and per-source depth.

Sources (all keyless):

| Source | Covers | Notes |
|---|---|---|
| **OpenAlex** | all fields (blockchain, CS, econ, bio…) | own taxonomy (domain -> field -> subfield -> topic); filterable by `primary_topic.id`, `topics.field.id`, `type`, `primary_location.source.type`, `language`; carries abstracts |
| **Europe PMC** | bio / medicine | best depth for life sciences |
| **bioRxiv / medRxiv** | preprints | plain JSON API |
| **ClinicalTrials.gov v2** | trials | free JSON |
| **NBER** | economics working papers | RSS, 47 KB |
| **RBI** | India finance press releases | RSS, 54 KB - rate decisions and circulars |
| **arXiv q-fin** | `q-fin.PM` portfolio management, `q-fin.RM` risk management, `q-fin.EC` economics (also GN/MF/ST/TR) | supplement only |

`[RESEARCH]` OpenAlex abstracts arrive as an **inverted index** (word -> positions) and must be reconstructed; and its topic taxonomy is **not** arXiv's categories.
`[RESEARCH]` **arXiv RSS is daily-announcement only** (verified: 200, valid feed, 0 items outside the window) and the arXiv API hard-`429`s - usable as a daily supplement, not a poll target.


**Dropped:** Notes (no content), Topics (folded into RSS), Alerts (folded into Watchers), Money (became M&M).

---

## 5. Radar horizontal tabs (proposed built-ins)

Tabs are saved filters: over tags, or over the matches of a rule. Built-ins are seeded rows; user-created tabs and rules add more (ADR 0003).

| Tab | Filter | Research |
|---|---|---|
| All | curated, balanced | `[RESEARCH]` curation + cap |
| Incidents | `incident` (status pages + maintenance language in SMS/news) | `[RESEARCH]` status sources + maintenance lexicon |
| Money | `transaction` + `market` | — |
| Expenses | `expense` | `[RESEARCH]` detection from personal SMS |
| Bills | `bill` | — |
| Promo | `promo` | see §6 |
| Travel | `travel` | — |
| News | `news` (not already surfaced under another tab) | `[RESEARCH]` dedupe across tags |
| Updates | `updates` | — |
| Social | `social` | `[RESEARCH]` provider |
| Personal | `personal` | — |

`[RESEARCH]` whether an event matching several tags appears in several tabs (likely yes) and how that interacts with the curated `All`.

---

## 6. What counts as promotional

Beyond SMS offers — and this is the tag that most needs cross-source sourcing:

- **Discounts / coupons** — % off, flat discount, promo code, cashback.
- **Sale events** — Big Billion Day, Great Indian Festival, Prime Day, festive sales (Diwali, Pongal, Onam), end-of-season, Black Friday. Episodic and mostly **national or regional** -> should come from news/RSS, not SMS.
- **Loyalty** — points earned/expiring, tier upgrades, memberships.
- **Launches** — new credit card, NFO/new fund, product launch, title release.
- **Local / town-level** — mall or store openings, exhibitions, food festivals, melas.
- **Travel promos** — fare sales, hotel deals, holiday packages, card travel offers.
- **Banking promos** — card offers, no-cost EMI, loan-rate offers, FD rate promos.
- **Win-back / referral / trials.**

Two useful axes: **scope** (national / state / city / store) and **validity window** — both needed to rank and expire them.
`[RESEARCH]` where sale-event calendars come from (news detection vs a curated JSON), and whether launches deserve their own tag separate from discounts.

---

## 7. Architecture work still unowned

- ~~Timeline model: tab = saved filter, rule = saved filter + delivery.~~ **Settled by ADR 0003** — a tab is a saved filter, a rule is a condition with an optional delivery, and the two are independent axes: delivery is the interruption, surfacing is a read-time projection.
- Dedupe key fix `(source, url ?: title)` + re-ingest.
- Cross-source classification so news items land in Money / Promo / Travel / News / Incident.
- Place extraction per item + home-place config + radius ∪ picked.
- Shared shell: search + per-tile action bar driven by a per-tile config model.
- Curated default view; TabStrip horizontal scrolling.

---

## 8. Per-tile config model (the shell contract)

Everything visual on a tile is declared, so the shell can render search + the action bar + the settings screen generically:

```
TileConfig(
  id, title, glyph, accent,
  searchHint,              // what search means inside this tile
  actions: [ActionKind],   // context action bar, in order
  settings: [SettingSpec], // typed entries rendered by the Settings screen
  query: TileQuery,        // what the body renders (tag / place / time filter)
  ruleScope,               // tag stamped on rules created from this tile
)
```

`ActionKind` = `Search · Sync · AddRule · Settings` plus tile-specific adds (`AddLocation`, `AddSource`, `AddPair`, `AddCategory`, `AddEvent`, `AddTopic`, `AddAccount`, `AddStation`, `AddStatusSource`).
`SettingSpec` = typed entry (`PlaceList`, `Radius`, `SourceList`, `PairList`, `CategoryList`, `TopicList`, `Retention`, `QuietHours`, `Quota`, `Toggle`).

**Where Settings lives:** `Settings` is itself a tile whose body is the **aggregate of every tile's `settings` specs**, grouped by tile. Each tile's `Settings` action opens that tile's section. Same table, two entry points - so per-tile config and global settings never drift.

**Shell layout (every tile, every depth).** The shell is fixed; only the actions change:

```
[ search field ]      <- TileConfig.searchHint
[ action bar ]        <- TileConfig.actions, left to right
[ body ]              <- TileConfig.query
```

- Search + action bar appear on the **root screen, every detail screen, and the settings screen** - not just the root. (Weather: root, each place detail, and the root header all carry Sync.)
- `Settings` is an `ActionKind`, so it appears in the bar; tapping it opens this tile's section of the aggregate Settings tile.
- Actions are **per tile and per depth** - e.g. the root action bar may offer `AddLocation` while a detail screen offers only `Sync` and `AddRule`.
- A tile can add its own actions to the standard four (`Search · Sync · AddRule · Settings`), including `Scan`.


| Tile | Search scope | Actions | Settings |
|---|---|---|---|
| Radar | events (text, entity) | Search, Sync, AddRule, Settings | dedupe, curated cap, retention |
| News | news text + place | Search, AddFeed, Locations, AddRule, Settings | selected locations, radius, surrounding-state sample, feed list |
| M&M | pairs, instruments, market news | Search, AddPair, AddRule, Settings | tracked pairs, instruments, refresh cadence |
| Expenses | merchant, category, amount | Search, AddCategory, AddRule, Settings | categories, budgets/quotas, source tags |
| Travel | destination, event | Search, AddEvent, AddRule, Settings | tracked festivals, off-season data, home airports |
| Weather | location | Search, AddLocation, Sync, AddRule, Settings | location list, units |
| Papers | title, author, topic | Search, AddTopic, Sync, AddRule, Settings | topics/fields/journals/authors, per-source depth |
| Watchers | rules and watchers | Search, NewWatcher, Sync, Settings | rule list, quotas, schedules |
| RSS | feed or item | Search, AddSource, Sync, Settings | sources, retention, bookmarks, catalog browse |
| Social | account, post | Search, AddAccount, AddRule, Settings | accounts, providers |
| Radio | station | Search, AddStation, Sync, Settings | stations, pinned |
| Incidents | incident text | Search, AddStatusSource, AddRule, Settings | status sources, maintenance lexicon |
| Settings | all config | Search | (is the aggregate) |
| More | - | - | - |

### News is not done - tile-internal open items
- **Location hierarchy missing.** Must render `city/town -> state -> sampled surrounding states (with "more") -> country`, with national events pinned top, per the Places config (radius union explicit picks). Place extraction per item is the prerequisite.
- **More sources.** Bundle from `awesome-rss-feeds` (CC0): India plus a few interests, parsed from OPML into seeded `sources`; the rest opt-in. Grouping is ours.
- **`More` behaviour.** When a location tier is truncated, `More` opens that tier as its own filtered view rather than expanding endlessly in place.

---

## 9. Gaps vs the original spec

### Missing entirely
- **Wallet** - only a placeholder in §4.12. Wanted: FD/RD, stocks and funds (CAMS), credit-card EMIs (SMS-derived), FD-rate checks via Exa/Tavily roughly every 6 months plus suggestions, **virtual wallets** with quotas that deduct per spend and shift red/yellow/green as they fill, default categories (rent, groceries, utilities), **receipt-image import** (share an image -> parse UPI id / merchant / description), and the dual-phone problem (spending phone is not the app phone).
- **Hushread** - the other pre-curated, categorised feed list. Not checked. `[RESEARCH]`
- **`Scan`** - a standard action in the original action bar; omitted from the §8 `ActionKind` list.
- **Per-tile sync on Weather square tiles** - each place tile has its own refresh, not just header/detail/root.
- **The Hindu as a travel/event source** - called out explicitly for festival and event coverage.

### Thin
- News location hierarchy is flagged open but not designed: tier rendering, per-state `More`, pinned national events.
- Tag composition: multi-tag is stated, but the union/intersection rule that builds a view is not.
- Retention numbers: rules/watched 1-2 years, everything else roughly 6 months.

### Assumptions needing confirmation
- **Alerts merged into Watchers**, with Messages as the notification inbox. The original spec left Alerts open ("a notification inbox showing rule matches, incident reports?") - it may deserve its own screen.
- Notes and Topics dropped, Money renamed M&M, Expenses added.

---

## 10. Tagging (pluggable classifier)

Tagging is what makes one store composable: an item is tagged once at ingest, and every tile reads it by tag. So the tagger is an **interface, not an implementation** - heuristics now, a small local model later, never a rewrite of the tiles.

### 10.1 The interface

```kotlin
interface Tagger {
    val id: String                 // "heuristic-v1", "embed-v1", "gemma-v1"
    suspend fun tag(input: TagInput): TagResult
}

data class TagInput(
    val text: String,              // title + body, joined
    val source: SourceKind,        // SMS, RSS, JSON, WEB
    val sender: String? = null,    // SMS sender / feed name
    val language: String? = null,
)

data class TagResult(
    val tags: Set<String>,         // multi-valued, never exclusive
    val entities: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val confidence: Float = 1f,
    val taggerId: String,
)
```

The interface is **suspended and offline-capable**, so a model-backed implementation fits the same call site.

### 10.2 Strategy ladder

| Impl | Cost | When |
|---|---|---|
| `HeuristicTagger` | zero | now - keyword/lexicon + source priors |
| `EmbeddingTagger` | ~10 MB | when heuristics plateau |
| `LlmTagger` (Gemma-class) | heavier | the hard minority only |
| `CascadeTagger(HeuristicTagger, LlmTagger)` | adaptive | escalate only below a confidence threshold |

`CascadeTagger` is the target shape: cheap pass over everything, model only on what the cheap pass is unsure about.

### 10.3 Wiring

One binding in `AppContainer`; nothing else changes:

```kotlin
val tagger: Tagger by lazy { HeuristicTagger() }   // swap: CascadeTagger(...)
```

### 10.4 Rules

This section is about the **tagger's** rules — how classification behaves. The user-facing rules engine (conditions over stored items, matches materialised into `item_rules`) is ADR 0003, not this.

- Deterministic and fast (runs per item at ingest, on device, no network).
- **Never blocks ingest** - a tagger failure stores the item with a `news`-only or source-default tag and a flag, rather than dropping it.
- `taggerId` is stored on the event, so items can be **re-tagged** when a better implementation lands.

### 10.5 Tag taxonomy

Tags fall into **three groups**, not one list. Conflating them is what made
"is a Cloudflare outage `incident` or `news`?" look like a choice - it is
neither. It is subject `tech` + nature `incident`.

| Group | Who assigns it | Examples |
|---|---|---|
| **Subject** - what it is about | the **source**, when it is a specialist feed; otherwise the tagger, per item | `finance` (money & market), `tech`, `travel`, `weather`, `paper` |
| **Nature** - what kind of thing it is | the **tagger**, from the content; multi-valued, soft cap 3-4 | `incident`, `announcement`, `promo`, `maintenance`, `official`, `personal`, `expense` |
| **Marker** - structural, never inferred | the ingest path | `news` |

**`news` is a marker, not a subject.** A general publication declares *no*
subject: The Hindu runs a markets story and a cricket story out of the same
feed, so its subject comes from the content. Mint Markets is a specialist feed,
so it declares `finance` for everything it publishes. `news` says only "this is
editorial content".

That is what gives the two tiles their relationship:

```
News tile = marker `news`        (everything editorial, ranked by place)
M&M tile  = subject `finance`    (a lens over the same corpus)
```

A Mint article is `news` + `finance` + a nature, so it appears in **both**. Tabs
and tiles are allowed to overlap; an item is not confined to one.

**Consequences of the split**

- The source never declares a nature. A status page declares subject `tech`;
  whether a given row is `incident` or `maintenance` is the tagger's call from
  the text.
- Status rows stop being uniformly `incident` - scheduled maintenance, in
  progress and resolved are different natures.

**Resolved**

- **SmsClass *is* the nature vocabulary for SMS** - `PERSONAL`, `OTP`, `TRANSACTION`,
  `BILL`, `TRAVEL_LOGISTICS`, `UPDATES`, `PROMOTIONAL`, `SPAM_FRAUD`. There is no
  second vocabulary to reconcile: the SMS classifier is simply the nature source
  for SMS, exactly as the tagger is for feeds. Tag names align on the lowercase
  form (`promo`, `bill`, `transaction`, `otp`, `spam`, `update`). `TRAVEL_LOGISTICS`
  is the one member carrying subject information too - it becomes subject `travel`
  plus a nature, and is the case to handle when SMS-side tagging is built.
- `travel` is a **subject** (bookings, flights, festivals, travel offers), never a
  nature - a flight deal is `travel` + `promo`.

**Deferred**

- The composition rule (union vs intersect) when a tile reads several tags - see
  §9. Deliberately postponed: the service tiles come first.

---

## 11. Store schema

### 11.1 IDs: ULID, not autoincrement

Every ingested item gets a **ULID** primary key.

- Lexicographically sortable by creation time - ranges work without a separate sequence.
- Collision-free **offline**, so two devices never clash on merge.
- Safe to export/import and to join across the cold archive (§12) without remapping.
- **Library, not hand-rolled.** `com.aallam.ulid:ulid-kotlin` (MIT, Maven Central, released Feb 2026, Kotlin-native with a JVM artifact; repo `aallam/ulid-kotlin`, actively maintained). Gives `ULID.nextULID()` and monotonic/strict-monotonic generators.
  - Runner-up: `com.github.f4b6a3:ulid-creator` (MIT, Java, more usages, Feb 2026) - fine, but Java-first and heavier for a Kotlin-only app.
  - Rejected: `de.huxhorn.sulky.ulid` - last release Dec 2021.
  - Note: **UUIDv7 is not a substitute** in Kotlin 2.2 (`kotlin.uuid.Uuid` is v4 only), and doesn't give the 26-char sortable string form.

### 11.2 Tables

```sql
-- ingested once
CREATE TABLE items (
  id           TEXT PRIMARY KEY,     -- ULID
  source_id    TEXT NOT NULL,
  url          TEXT,
  title        TEXT NOT NULL,
  body         TEXT,
  published_at INTEGER,
  fetched_at   INTEGER NOT NULL,
  lang         TEXT,
  place_id     TEXT,
  dedupe_key   TEXT NOT NULL,        -- url ?: source_id||':'||title
  bookmarked   INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX idx_items_dedupe    ON items(dedupe_key);
CREATE INDEX        idx_items_published ON items(published_at DESC);

-- registry: one row per tagger implementation + version
CREATE TABLE taggers (
  id         TEXT PRIMARY KEY,       -- "heuristic-v1", "ml-v2", "cascade-v1"
  kind       TEXT NOT NULL,          -- REGEX | HEURISTIC | ML | CASCADE | LLM
  name       TEXT NOT NULL,
  version    INTEGER NOT NULL,
  active     INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

-- append-only: re-tagging is an INSERT, never an UPDATE
CREATE TABLE item_tags (
  item_id    TEXT    NOT NULL,
  tag        TEXT    NOT NULL,
  tagger_id  TEXT    NOT NULL,
  confidence REAL    NOT NULL DEFAULT 1.0,
  entity     TEXT,
  tagged_at  INTEGER NOT NULL,
  PRIMARY KEY (item_id, tag, tagger_id),
  FOREIGN KEY (item_id)   REFERENCES items(id)   ON DELETE CASCADE,
  FOREIGN KEY (tagger_id) REFERENCES taggers(id)
);
CREATE INDEX idx_item_tags_tag ON item_tags(tag, item_id);
```

**Why append-only.** Tags carry provenance. Re-tagging inserts new rows, so you can compare v1 against v2 on the same corpus, roll back, and keep a tag that the new tagger no longer emits - all without a data migration.

### 11.3 Versioning

`kind` + `version` is the mapping you described: `REGEX → v1`, `HEURISTIC → v1, v2, v3`, `ML → v1`, `CASCADE → v1`. Two rows exist for the same `kind` when the implementation changed; the newer one takes `active = 1`. Storing them separately means you can ask "everything tagged by an ML model" or "everything from heuristic v2" without string-matching an id.

### 11.4 Reading "current" tags

A re-tag must not make items look untagged while it rolls out, so reads go through a view:

```sql
CREATE VIEW item_tags_current AS
SELECT t.* FROM item_tags t
WHERE t.tagger_id = (SELECT id FROM taggers WHERE active = 1);
```

Items with no row from the active tagger simply fall back to their newest existing row. Tiles then read one shape:

```sql
SELECT i.* FROM items i
JOIN item_tags_current t ON t.item_id = i.id
WHERE t.tag = 'weather'
ORDER BY i.published_at DESC;
```

### 11.5 Re-tagging

Idempotent and resumable, so it can run in batches inside WorkManager:

```sql
SELECT i.id FROM items i
WHERE NOT EXISTS (
  SELECT 1 FROM item_tags t
  WHERE t.item_id = i.id AND t.tagger_id = :activeId
)
LIMIT 200;
```

Old tagger rows are pruned only after the new tagger is active and verified - keep active + previous, drop older.

### 11.6 Migration from the current schema

1. `events.category` → an `item_tags` row under a source-default tagger (`source-v1`): feeds → `news`, status → `incident`.
2. `events.type` dropped; the duplicated rows go away with the old unique index, replaced by `dedupe_key`.
3. New columns (`id` ULID, `dedupe_key`, `bookmarked`) backfilled; then re-ingest.

---

## 12. Parked

Deliberately deferred, recorded so the reasoning and the sources outlive the session.

### 12.1 Worldwide gazetteer

**`places.dat` is India only.** It is built from GeoNames `IN.zip` +
`alternatenames/IN.zip`, so a story about Sri Lanka, Nepal or the Gulf resolves to
*no place at all* — not a wrong place, which is better, but a hard ceiling once
non-India news is in the store.

| Option | Coverage | Licence | Notes |
|---|---|---|---|
| GeoNames `cities15000` / `cities5000` / `cities1000` | worldwide | CC BY 4.0 | 25k / 50k / 150k places. Same columns, same licence, same builder — this is a bigger input file and a looser filter, not new code. |
| GeoNames `allCountries.zip` | worldwide, everything | CC BY 4.0 | ~2 GB raw, ~1.5 M places. Filter hard; never ship raw. |
| **Geoapify localities** — `geoapify.com/data-share/localities/` | ~245 country zips + `no-country.zip` | **unverified** | Per-country archives only, **no bulk download**. Largest: `cn.zip` 36 MB, `in.zip` 25 MB; all dated 2025-09-17. **The index page states neither the internal format nor the licence** — `readme.html` and one archive would have to be inspected first. |

**Recommendation:** GeoNames. The builder, the column layout, the ASCII-matching
rule and the licence all already hold, so worldwide becomes "download a larger
input and relax `POP_MIN`". Geoapify is only worth revisiting if its localities
turn out to be materially better than GeoNames' towns — currently unknown.

Nothing here changes until a non-India place name actually appears in the feed.




