# Plan 0002 — Sources, Items and Rules

- **Status:** Requirements written; slice 1 complete and merged (`43517af`), device-verified; slice 2 next
- **Date:** 2026-09-15
- **Depends on:** ADR 0003 (sources/items/rules interfaces), ADR 0001 (zero-cost policy)
- **Companion:** ADR 0002 (the superseded `rules`-as-sources model)

---

## How to use this file (RALPH loop)

This file is the state that survives context loss. On resume:

1. **Read** the Progress log and the Next action below — do not re-derive the design; ADR 0003 holds it.
2. **Pick** the first unchecked task in Slices.
3. **Do** it, then run the gates in Verification.
4. **Log** what landed (one line: date, task id, commit if any, evidence) and update Next action.
5. **Repeat** until the slice list is done.

Rules of the loop: one slice at a time; never leave the tree non-building; commit only when asked; never edit application code without explicit permission.

**Current state:** slice 1 landed as `43517af` on `main` (fast-forward from `feat/sources-rules`, worktree removed). 250 tests green in-tree *and* from a fresh clone; migration v10→v11 verified on the device with `room_master_table.identity_hash` matching `11.json`. ADR 0003 and this plan are still uncommitted in the main tree.
**Next action:** slice 2 — the predicate language in `core/rules/` (item predicates, series predicates, `all`/`any`, unknown-key rejection, Unicode-safe text matching), pure Kotlin with unit tests and no storage or UI coupling.

---

## Requirements (EARS)

Templates: **Ubiquitous** = always; **When** = event-driven; **While** = state-driven;
**Where** = optional feature; **If/then** = unwanted behaviour.

### Sources

| id | type | requirement |
|---|---|---|
| S1 | Ubiquitous | The system shall represent every producer as a source row whose identity is stable and independent of its transport. |
| S2 | When | When a source is due on its interval, the system shall fetch it, map its payload to canonical items, and store them. |
| S3 | Ubiquitous | The system shall keep a source's kind, fetch parameters, paging style and field mapping in `spec_json` as data, with one adapter per kind. |
| S4 | Where | Where a spec declares a stateful transport, the system shall fetch it through the platform that holds the session. |
| S5 | If/then | If a source fails or yields no items, then the system shall record the attempt and its reason without affecting other sources or the store. |
| S6 | Ubiquitous | The system shall reference a secret by key name and shall never store a secret in a spec or in the database. |
| S7 | Ubiquitous | The system shall support the paging styles `none`, `next_url`, `cursor`, `offset` and `highwater`. |
| S8 | Ubiquitous | The system shall express field mapping as paths plus a named, fixed set of transforms, and not as per-source code. |

### Items and identity

| id | type | requirement |
|---|---|---|
| I1 | Ubiquitous | The system shall give every item an `id` that is stable across re-fetch and independent of item content. |
| I2 | When | When a source declares an id for an item, the system shall use it; otherwise the system shall hash a per-transport key. |
| I3 | Ubiquitous | The system shall retain the pre-hash identity key for diagnosis. |
| I4 | If/then | If an incoming item's identity matches a stored row, then the system shall ignore the write (first write wins). |
| I5 | Ubiquitous | The system shall freeze an item's values at ingest, so a later fetch cannot rewrite existing values. |
| I6 | Ubiquitous | The system shall record which fields a kind can supply, so an absent field is distinguishable from an unfetched one. |
| I7 | Where | Where a kind's values can be revised by the producer, the system shall allow an update in place of first-write-wins. |

### Predicates

| id | type | requirement |
|---|---|---|
| P1 | Ubiquitous | The system shall express a condition as predicates composed with `all` / `any`. |
| P2 | Ubiquitous | The system shall support item predicates over subject, nature, marker, mention, source, field and text. |
| P3 | Ubiquitous | The system shall support series predicates over a window: crossing, delta, and min/max over a period. |
| P4 | When | When a condition contains a text predicate, the system shall mark that rule as enrichment-sensitive. |
| P5 | If/then | If a text predicate could cause unbounded backtracking, then the system shall bound the input or the match so ingestion cannot stall. |
| P6 | Ubiquitous | The system shall match text with Unicode-aware boundaries and the `(?u)` flag. |
| P7 | Ubiquitous | The system shall reject unknown keys in a condition or action at write time. |

### Rules and evaluation

| id | type | requirement |
|---|---|---|
| R1 | Ubiquitous | The system shall evaluate rules only against stored items, and shall never fetch on behalf of a rule. |
| R2 | When | When an item is ingested, the system shall evaluate enabled item rules against that item only. |
| R3 | When | When an item is enriched, the system shall re-evaluate only text-predicate rules, scoped to the enriched items. |
| R4 | When | When a rule is edited, the system shall evaluate it over history and shall persist no matches from that evaluation. |
| R5 | Ubiquitous | The system shall store matches append-only in `item_rules`, keyed by item and rule. |
| R6 | If/then | If a rule is disabled or deleted, then the system shall stop matching it without altering items or other rules' matches. |
| R7 | Ubiquitous | The system shall evaluate conditions with a pure evaluator holding no Android dependency. |

### Delivery and surfacing

| id | type | requirement |
|---|---|---|
| D1 | Ubiquitous | The system shall treat delivery as an interruption with a single target, not as a storage location. |
| D2 | Ubiquitous | The system shall treat surfacing as a read-time projection over the store. |
| D3 | When | When a rule matches with a position, the system shall raise that item's prominence in views that honour position. |
| D4 | Ubiquitous | The system shall let a view select items by tag or by rule match. |
| D5 | Ubiquitous | The system shall keep tiles and Radar tabs driven by their own filters, and shall replace none of them. |

### Monitors

| id | type | requirement |
|---|---|---|
| M1 | Where | Where a rule contains a series predicate, the system shall evaluate a window of stored observations of that series. |
| M2 | If/then | If a window contains a gap, then the system shall not fire a threshold crossing across that gap. |
| M3 | Ubiquitous | The system shall bound a monitor's window by the source's retention period. |
| M4 | Ubiquitous | The system shall key a series by a declared field so a window query is indexed rather than a full scan. |

### Enrichment

| id | type | requirement |
|---|---|---|
| E1 | Ubiquitous | The system shall keep enrichment a single owner that fills payload and never predicates. |
| E2 | When | When an item has no text and its kind warrants enrichment, the system shall attempt it once and record the outcome. |
| E3 | If/then | If enrichment has been attempted and failed, then the system shall not retry it automatically. |
| E4 | Ubiquitous | The system shall mark an item's text as publisher-supplied, extracted, or absent. |
| E5 | If/then | If a second enricher is present, then the system shall skip enrichment for items it already marked extracted. |

---

## Slices

### Slice 1 — schema v11 and the rename (complete)

**Rename scope (decided).** The sources concept is renamed throughout, not just the table:
`RuleEntity`→`SourceEntity`, `RuleDao`→`SourceDao`, `RuleRepository`→`SourceRepository`,
`RuleSeeder`→`SourceSeeder`, and `core/rules/`→`core/sources/` (`RuleKind`→`SourceKind`,
`RuleSpecs`→`SourceSpecs`, `RuleSpec`→`SourceSpec`, `RuleSources`→`SourceKeys`; `GnewsUrl` keeps its
name). The collision forces it — `RuleEntity` cannot mean both a source and a rule — and the
half-measure (`SourceEntity` with a `RuleKind` field) would be a lie. `core/rules/` is then free for
the evaluator the ADR places there in slice 2.

- [x] T1.1 Migration v10→v11: `ALTER TABLE rules RENAME TO sources`; create `rules` and `item_rules`; add the two `item_rules` indexes. (S1, S3, R5)
- [x] T1.2 `SourceEntity` (today's `RuleEntity`), new `RuleEntity`, `ItemRuleEntity`. (S1, R5)
- [x] T1.3 `SourceDao` (was `RuleDao`), new `RuleDao`, `ItemRuleDao`; all SQL as consts in `Sql.kt`. (S1, R5)
- [x] T1.4 `SourceRepository` (was `RuleRepository`, behaviour unchanged incl. locked seeds). The new `RuleRepository` was **written and then deleted** in the cleanup pass (`90ff28c`): it had no caller, and slice 3 names `RuleWriter` for writing matches, so its job was undefined. `RuleEntity`/`RuleDao`/`ItemRuleDao` stay — the entities *are* this slice, and the DAOs are Room's compile-time check on the `Sql.kt` statements. Condition/action validation still belongs to slice 2, where the condition schema exists.
- [x] T1.5 Rename `RuleSeeder`→`SourceSeeder`; update every call site (`FeedIngestor`, `UserFeedsSection`, `RssScreen`, `AppContainer`, label resolution, `Retagger`), so behaviour is identical. (S1)
- [x] T1.6 Export `11.json`; `MigrationSchemaTest` green against the new chain. (S1)
- [x] T1.7 Gates + **fresh-clone build** before calling the slice done. (250 tests, 53 tasks executed from a clean clone; device migration confirmed by matching identity hash)

### Slice 2 — the predicate language (pure)

- [ ] T2.1 Item predicates: subject, nature, marker, mention, source, field, text. (P2)
- [ ] T2.2 Series predicates: crossing, delta, min/max over window. (P3)
- [ ] T2.3 `all` / `any` composition and unknown-key rejection. (P1, P7)
- [ ] T2.4 Text matching with Unicode boundaries, `(?u)`, and a bounded input/match. (P5, P6)
- [ ] T2.5 Enrichment-sensitivity flag derived from the condition. (P4)

### Slice 3 — evaluator and materialisation

- [ ] T3.1 Pure evaluator over a supplied item + tags + mentions + fields. (R1, R7)
- [ ] T3.2 Ingest evaluation of new items; `RuleWriter` writing `item_rules` append-only. (R2, R5)
- [ ] T3.3 Enrichment-triggered re-evaluation, scoped to text-predicate rules and the enriched items. (R3)
- [ ] T3.4 Dry run: evaluate over history, persist nothing. (R4)

### Slice 4 — source spec

- [ ] T4.1 `page` block and its adapters: none / next_url / cursor / offset / highwater. (S7)
- [ ] T4.2 `map` with named transforms (inverted_index, dateparse, concat_authors). (S8)
- [ ] T4.3 Kind capabilities declared, so absent fields are interpretable. (I6)
- [ ] T4.4 `session` declaration for stateful transports. (S4)
- [ ] T4.5 Identity: source-declared id, else per-transport key hash; first-write-wins. (I1–I5)

### Slice 5 — delivery, surfacing, monitors

- [ ] T5.1 Alerts as a view over `item_rules`. (D1, D2)
- [ ] T5.2 Rule-driven emphasis (position) in views that honour it. (D3)
- [ ] T5.3 A view can select by rule match, not only by tag. (D4)
- [ ] T5.4 Monitors: series window evaluation, gap handling, retention bound, indexed series key. (M1–M4)

---

## Verification

```bash
cd android && ./gradlew :app:ktlintFormat
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest ktlintCheck
```
All three must pass. A slice is not done until it also builds from a **fresh clone**
(`git clone` to a temp dir) — the main tree has twice passed on files that were
never committed. Device claims are verified with data (`adb`, `uiautomator dump`,
the pulled DB plus `-wal`), never inferred.

---

## Constraints and lessons already paid for

- **Do not touch application code without explicit permission.** Design discussion is welcome at any time; edits are not.
- **Commit only when asked.** Work can sit uncommitted indefinitely.
- **`:root` hygiene in CSS** — an unclosed block silently swallows the rest of a stylesheet (cost: the font switcher appeared to do nothing while parsing 3 of 234 rules).
- **Room:** no `DEFAULT` on `ADD COLUMN` (validation compares defaults); a view's SQL text must match Room's generated text exactly; migration DDL must be data so tests can replay it.
- **Android regex:** `(?U)` throws at runtime while JVM accepts it — use `(?u)`; `\b` is ASCII-only and never fires around Devanagari.
- **aapt:** an asset ending `.gz` is decompressed and renamed at package time — ship `.dat`.
- **Worktrees:** commit inside before removing; never `remove --force`. KSP has been observed to fail in fresh directories, so verify from the main tree if a worktree build fails there.
- **Gradle/Room KSP:** a missing file referenced by committed code shows up as `No property named value was found in annotation Query` in fresh checkouts, not as an obvious compile error.

---

## Deferred (not in this plan)

- **World-coverage feeds** — Al Jazeera, DW, France24, SCMP, CNA, NHK, Diplomat; curl-verify before seeding, and balance Western/non-Western by design.
- **Per-feed drill-down** — tapping a source row shows that source's items; today rows are health-only and items land in the general list.
- **Ministers/politicians** — Wikidata-derived roster with validity windows; research in `docs/research/place-inference-signals.md` §3.1.
- **APK size** — no `buildTypes` block exists, so R8 has never run; release/debug are both unminified (19 dex files ≈ 19 MB of a 21 MB APK).
- **Mockup font judgement** — headless Chrome does not download webfonts, so the faces must be judged in a browser with network.
- **Bot-protection bypass** — measured 2026-09-15 from a residential IP. Downdetector is a genuine
  Cloudflare **JS challenge** (`cf-mitigated: challenge`, `Just a moment`, `cf_chl_*`) and no TLS
  impersonation can solve it; the Akamai 403s (NDTV, Telegraph, Zee, Times Now, Anandabazar) are
  **edge/TLS gates** — UA-independent, no `_abck`, so not Bot Manager — which *are* impersonation's
  class. Deferred deliberately: the real need is **flights, trains and job boards**, not news, because
  news already has free feeds that work. On resume, the on-device route is `zhkl0228/impersonator`
  (Java bctls/OkHttp, Maven Central) rather than curl-cffi, which is Python and cannot ship in the APK.
  Unproven — needs the throwaway-venv spike before any Android work. ThePrint's catalogued
  "Cloudflare challenge" is stale: `/feed/` now 301s to the homepage and its real feed URL needs
  re-discovery.

---

## Progress log

Append-only. One line per landed change.

- 2026-09-15 — Plan written; requirements in EARS, loop header added. No implementation started.
- 2026-09-15 — Research: bot-protection bypass classified and deliberately deferred (news does not need it). Slice 1 starting.
- 2026-09-16 — **Slice 1 complete** as `43517af` (worktree `feat/sources-rules`, ff-merged, worktree removed). Rename went beyond the table: `RuleEntity`/`RuleDao`/`RuleRepository`/`RuleSeeder` → `SourceX`, and `core/rules/` → `core/sources/`, because `RuleEntity` could not mean both a source and a rule. Evidence: 250 tests in-tree and from a fresh clone (53 tasks executed), `11.json` exported, and on-device v10→v11 with `room_master_table.identity_hash` equal to `11.json`'s — the check that failed on this device before. `sources` kept all 13 rows and their `interval_sec`; ingest continued (`events` 4511→4710, `item_tags` 22282→22570, `mentions` 2724→2942) so tagger and extractor survived the rename.
- 2026-09-16 — Slice 1 review (oracle) + cleanup landed as `90ff28c` (worktree `refactor/sources-cleanup`, ff-merged, removed). Closed: the rest of the `rule`→`source` identifiers (incl. the `UserFeedsSection` composable API); the `TagSourceKind` alias *and* its cause — `core.tag.SourceKind` was never a source kind, so it is now `Transport`; deleted premature `RuleRepository` and dead `Migrations.CURRENT_VERSION` (which was wrong by its own comment: keys are target versions, so newest is `max(keys)`), replacing it with a test that pins that invariant; `MigrationSchemaTest` now asserts `dflt_value`, closing the DEFAULT trap the migration comments describe. Docs: `ARCHITECTURE.md` corrected to ADR 0003 (`3007a79`); ADR 0003's status no longer claims nothing is implemented. 251 tests green in-tree and from a fresh clone.
- 2026-09-16 — Two tests written for the rule DAOs were **deleted as tautologies** in that pass: they defined fakes that reimplemented `INSERT OR IGNORE` in Kotlin and asserted the fakes, so they would have kept passing if the composite primary key were removed or `onConflict` became `REPLACE`. The fact they pretended to check is genuinely covered — `MigrationSchemaTest` compares every exported table's primary key against `PRAGMA table_info` over real SQLite — so nothing was lost by removing them.
- 2026-09-16 — Device verification of `90ff28c`: installed over the live v11 DB, app opened with no Room error, still at v11 with 13 sources. The frozen-value decision was proved rather than assumed — `PrefsStringCache` stores each logical key as `<key>.at` + `<key>.value`, and the key sets were *identical* before and after, with all four `rule:<id>` body-cache keys intact and the retag cursor the only changed value (`heuristic-v9:r1` 29887→30347). Note: a naive substring check for `feed:rule-status` reports a false negative because of that `.at`/`.value` split.

### Known gaps from slice 1

- **`@Insert(onConflict)` is not verified.** The composite primary key is asserted against real SQLite, but Room's conflict mode is a one-word annotation no JVM test in this project can exercise (there is no Robolectric or in-memory Room anywhere; DAO tests would be fakes testing themselves). Confirm it on device in slice 3, when `RuleWriter` first writes matches — a `REPLACE` instead of `IGNORE` would overwrite `matched_at` and silently rewrite match history.
- **`feed:rule-status.value` reads `[]` on device** — the source-status list is empty, so source health dots have no data behind them. It was `[]` before this work too, so it is not a regression from the rename, but it is unexplained and worth a look on its own.
