# ADR 0006: One source model, one health record, per-kind surfaces

- Status: Proposed
- Date: 2026-09-21
- Supersedes parts of: `docs/ARCHITECTURE.md` §4.8 and §9 (Topics folded into RSS)
- Related: `docs/CODE-DESIGN-GUIDELINES.md` §2 (Open/Closed), §3 (Liskov), §6 (violations to fix)

## The problem, with evidence

"A feed" has **two owners**, and the screens re-unify them by hand:

| Fact | Owner A | Owner B | Glue |
|---|---|---|---|
| Which feeds exist | `FeedCatalog.SEEDS` (hardcoded `object`) | `sources` rows | `SourceSeeder` mirrors A into B |
| Health / last sync | `FeedIngestor.statuses` + prefs cache | `FeedIngestor.sourceStatuses` + prefs cache | `catalogByUrl[normalizeFeedUrl(url)]` matches **URL strings** at render time |
| Ingest | catalog loop | `refreshRssSources`, `refreshSearchSources`, `refreshGaugeSources` | four bespoke paths |
| View | `FeedRow(catalog status)` | `UserFeedsSection(sources)` | `includeSeeded` flag, `"Feeds"`/`"My feeds"` branch |

This session added a **third** health record (`sync_runs`) and then glued the UI
around the overlap (`includeSeeded`, URL matching) instead of removing one.

Consequences already observed:

- Sources reported "8/8 ok" while the RSS screen showed an empty list — two views
  of the same fact disagreeing (Liskov).
- The four seeded **Topics** rows (`kind = search`) sync but have **no surface at
  all**: nothing reads a non-`rss` kind, so they cannot be seen, edited, disabled
  or deleted.
- Adding a producer meant adding a *path*, not an adapter (Open/Closed).

## Decision

### 1. `sources` is the single owner of "what exists"

`FeedCatalog` stops being a runtime source of truth. The bundled set is **seed
data**, and seeded `sources` rows already are the model. Nothing reads
`FeedCatalog` at runtime; an installation already has all eight feeds as rows.

### 2. `sync_runs` is the single health record

`FeedStatus`, `statuses`, `sourceStatuses`, the prefs status caches and
`_lastSyncAt` are deleted. "When did this last run, did it work, how many items"
is one query over one table, and it already covers every kind — including SMS,
weather, FX and calendar, which the status flows never covered.

### 3. One ingest path for every kind

`rss` and `search` stop being special-cased loops and become adapters on the
`KindAdapter` map, like `weather`/`fx`/`device`/`calendar` already are. The
dispatch loop is the only place that decides "fetch now or skip", from the
source row's interval and the adapter's own cadence.

### 4. A kind owns its spec shape *and* its surface

Ingest is uniform; **authoring is not**. A subscription and a standing question
are different things to create:

| kind | spec shape | surface |
|---|---|---|
| `rss` | url, interval | **RSS** |
| `search` | query, query language, edition | **Topics** |
| `calendar` | region, provider | **Calendars** |
| `weather`, `fx`, `device`, `sms` | seeded specs | none (index only) |

`Sources` becomes the **index of all kinds**: last run, enabled state, and the
entry point into each kind's own surface. It is not where the RSS list lives.

**Topics is its own surface, not a facet of RSS.** The structure differs (a query
with language and edition, versus a URL) even though the *result* is the same
kind of thing: items. This corrects `ARCHITECTURE.md` §4.8/§9.

## Invariants

1. One fact, one owner: a source exists once, in `sources`.
2. A view may filter (`kind = 'rss'`), never re-assemble another model.
3. No view matches on URLs or string-normalises identity to borrow a fact.
4. Health is read from `sync_runs`; no view keeps its own copy.
5. A kind with no surface is still a first-class source (it appears in the index).
6. Adding a kind touches: an adapter, a spec shape, seed rows, and optionally a
   surface. Never the dispatch loop, never another kind's surface.

## Contracts

```kotlin
/** Every kind implements this; nothing else branches on kind. */
interface KindAdapter {
    val kindId: String
    fun parseSpec(json: String): SourceSpec
    fun identity(source: SourceEntity): String
    suspend fun ingest(source: SourceEntity, now: Long): Int
}

/** One row per run, per source — the only health record. */
data class SyncRun(sourceId, kind, startedAt, finishedAt, ok, itemsAdded, error)

/** What every surface reads. */
interface SourceIndex {
    fun observeAll(): Flow<List<SourceEntity>>
    fun observeLastRun(sourceId: String): Flow<SyncRun?>
}
```

## Rollout

1. **Adapters for rss + search.** Move the two loops onto `KindAdapter`. The
   ingestor's catalog/source loops collapse into the one dispatch. Behaviour must
   not change: same dedupe keys, same tags, same cache discipline.
2. **Health from `sync_runs`.** Point the screens at it; delete the status flows,
   `FeedStatus` and the prefs caches.
3. **Surfaces.** `Sources` = index of all kinds; `RSS` = `kind = 'rss'`;
   `Topics` = `kind = 'search'` (new, authored by query + language + edition).
   Absorb the single row treatment from the unmerged `feat/feeds-ui` branch
   rather than merging that branch and redoing it.
4. **Delete the glue**: `catalogByUrl`, `normalizeFeedUrl` for lookup purposes,
   `includeSeeded`, the `"Feeds"`/`"My feeds"` branch.
5. **Correct the docs**: `ARCHITECTURE.md` §4.8 (RSS), §9/§10 (Topics is a kind
   with a surface, not "folded into RSS" nor "dropped").

## Risks

- **It deletes code the screens currently read**, so screens move in the same
  slice. A half-done step leaves no health anywhere — the steps must land together.
- **Ingest is the riskiest surface in the app.** The adapter move must be
  behaviour-preserving; the existing dedupe/tag/cache tests are the guardrail,
  and a new test must pin that a catalog feed and a user feed of the same URL
  still dedupe to one row.
- **`feat/feeds-ui` conflicts** in the same files. Absorb its intent, close it.

## Tasks (tests first)

| # | Task | Guards |
|---|---|---|
| T1 | `RssKindAdapter` + `SearchKindAdapter`; move both loops onto the dispatch | existing ingest tests + dedupe parity |
| T2 | Delete the catalog loop; seeds remain the only definition of the bundled set | feed count and specs unchanged |
| T3 | `SourceIndex` reading `sources` + `sync_runs`; screens switch to it | a source with no run reads "never" |
| T4 | Delete `FeedStatus`, `statuses`, `sourceStatuses`, prefs status caches, `_lastSyncAt` | no reference remains |
| T5 | `Sources` = kind index (last run, enable/disable, entry points) | every kind appears, including ones with no surface |
| T6 | `RSS` surface = `kind = 'rss'` only | Topics rows never appear there |
| T7 | `Topics` surface = `kind = 'search'`: list, add (query + language + edition), enable/disable/delete | a seeded topic is visible and editable |
| T8 | Delete the glue (`catalogByUrl`, `includeSeeded`, label branch) | no URL-based identity lookup remains |
| T9 | Doc corrections | §4.8/§9/§10 agree with the code |

Do not ship T2 before T1, or T4 before T3.
