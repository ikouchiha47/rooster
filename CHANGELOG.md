# Changelog

Long-form notes for each commit. The commit subject stays short; everything after
`<!-- changelog -->` in a commit message is moved here by `.githooks/commit-msg`,
and entries read newest-first.

Notes marked *recovered from the pre-hook commit message* predate the hook.

<!-- entries -->
## feat(news): sectioned day paging with counts, schema v9

_2026-09-15_

Opening News no longer pays for all of Today to reach Yesterday. A headers
query (day + COUNT(1) over the indexed timestamp) loads first; Today opens
with the usual initial page and every other day starts its own keyset cursor
on tap — collapsed, never-opened days fetch zero rows. Headers re-query on
resume, so midnight rollover and sync drift correct themselves. Today's drift
is the N NEW pill: tap scrolls to Today and opens newest.

Schema v9 adds events.ingested_at (NULL, no default; migration backfills
publish time, new inserts write wall-clock) and rules.updated_at (rule edits
finally tracked). Reads use COALESCE. Timestamp audit: every table carries
Rails names now; places exempt as static.

Sync-bucket separators group Today by ingest run under one shared
SYNC_GROUP_MINUTES const the scheduler references too — one owner.

Verified: v9 live on device with zero null ingested_at, 222 tests green
(headers, paging, buckets, migration chain).

## feat(ui): centered forecast days with condition tints

_2026-09-15_

The 7-day strip's day columns left-leaned inside their own width (the column
was centered, the texts were not) and every day wore the same paper. Columns
are centered now, and each day carries its condition: straw tints for
clear/partly-cloudy, greige for cloud, dusty sky for rain, and solid Indigo
for 90%+ storm — all washed from the existing palette, no new hues.

Storm is the only band that flips to pale ink (paper2/pale-ink tokens,
~7.9/~4.9:1; ink-on-Indigo fails at ~2:1, so nothing else flips). No-data
days stay untinted, exactly as before.

Also centers the sync step text in the tile sync bar ("Refreshing feeds"
sat left in a full-width ink bar).

Verified on device: centered columns, indigo storm days readable, pale tints
elsewhere, no crash. 228 tests green.

## fix(tag): share mistag, google-news labels, fuller meta lines

_2026-09-15_

Three fixes in one pass, all reported from the same screenshots:

- Bare `share` leaves the finance vocabulary ("Doctors Share Concern"
  tagged a dengue story finance); `shares`, `share_price` and `market_share`
  stay. Tagger v8.
- Search-rule rows read GOOGLE NEWS instead of their query slug (KOLKATA as
  a source label read as a place). The outlet already rides in the headline
  ("... - NDTV"), verified row by row, so the label says where it came from.
- The meta line gets a third slot (2 was starving with tags plus mentions
  competing) and a mention echoing the rule query spends no slot, so a
  gnews:kolkata row never shows a KOLKATA chip.

Games vocabulary goes full-programme in the same pass (tagger v9): LA28's 35
sports, the winter set, Asian-Games staples (asian_games, asia_cup, t20i —
the T20I boundary miss that left AFG-IND untagged). Deliberately skipped:
bare ashes (funerals), shooting/shootout (crime), euro (currency).

Stale stopworded mention rows are deleted once by the BackfillWorker
(prefs-gated; the fix only removes matches, so no rescan), and the retagger
recovers rule tags for non-catalog sources with a recovery-versioned cursor.

Verified on device: dengue news-only, AFG-IND games, 155/155 gnews items
with news, ghost chips uniform at three slots, no crash. 192 tests green.

## fix(app): launch work off the main thread, stopword noisy towns

_2026-09-15_

The app ANR'd on cold start: the launch block ran retag, all seeds and the
mention backfill — including compiling a ~40k-alternative regex — on the
Main-bound lifecycleScope. Input froze past 5s and the process died. Every
heavy entry point now owns withContext(Dispatchers.IO), and the whole chain
moved into a persisted BackfillWorker that survives the app being closed
mid-walk (every step is cursor- or count-gated, so restarts resume).

Also stopwords six gazetteer towns that lose to their English doubles: along,
men, ali, kant, patra, met — each fired on device (ALONG on "along with").
Real towns of 2k–27k; the collision cost dwarfs the match value.

Verified on device: smooth navigation across all tabs, worker completes
(+31 tags on a warm run), no ANR. 192 tests green.

## feat(rules): universal source spec with Google News search, schema v8

_2026-09-15_

Rules slice v1 per ADR 0002: a `rules` table (id, name, kind, spec_json,
seeded, enabled) with two kinds — plain `rss` and Google News `search`.
Spec is validated JSON; unknown keys rejected so future kinds (scraper, API)
arrive as data plus an adapter, never a migration.

13 locked seeds: 4 search rules (Bangalore, Kolkata, Karnataka, West Bengal —
city/state level, country-top deliberately dropped as duplicative) plus 9 rss
imports mirroring the catalog. RuleRepository is the single owner enforcing
add-only seeds. FeedIngestor polls enabled search rules through the one
store/tag/mention path; retag recovers rule tags for non-catalog sources.

Verified on device: 13 locked rows seeded, 155 gnews items ingested, all
carrying `news` after the retag. 192 tests green.

## feat(app): chicken icon on black

_2026-09-15_

The app had no icon at all — manifest set no android:icon, so it wore the
system default. Now a Twemoji chicken (U+1F414) as an adaptive-icon foreground
on a solid black background, centered in the safe zone. Min SDK is 26, so
adaptive XML needs no legacy PNGs.

Verified: aapt validates the vector at build, installs clean. Launcher
drawer shot still pending — the swipe missed into an article.

## feat(ui): tinted ghost chips for the second layer

_2026-09-15_

Natures (incident, expense) and mentions (places, parties) shared one
treatment — plain grey micro text — and read as unfinished next to the filled
subject chips. They now share one tinted ghost chip instead: transparent fill,
1dp Rust outline, Rust text, same shape and padding as the subject chip, so a
row reads [filled TRAVEL] [outlined KOLKATA] with fill-vs-outline marking the
layer at a glance.

Rust is the only category accent with no subject meaning (teal is money,
mustard travel, plum tech, indigo paper), so it tints without borrowing. Line
mechanics untouched: one-line clamp, 2-slot budget, ordering, +N overflow,
uniform row heights.

Verified on device before committing: ghost chips render (JAMMU, PUNJAB +
EXPENSE side by side, GONDA), rows uniform, no crash. Only TagLine.kt touched.

## fix(mention): use (?u) flag, Android rejects (?U)

_2026-09-15_

The app crashed on first mention-index build with
PatternSyntaxException near index 3: Android's regex engine rejects the
(?U) (UNICODE_CHARACTER_CLASS) spelling the JVM accepts, so all 152 unit
tests stayed green while the device died. Lowercase (?u) (UNICODE_CASE) is
accepted everywhere and is the correct flag here — the lookarounds are
explicitly Unicode-aware regardless.

Verified on device before committing: no crash across Home, Services, News
(530 items with mentions), Weather (forecast + places + news), Messages,
Wallet/Me/Travel (blank stubs, confirmed by code, not by misclick). DB holds
66 party + 1096 place mentions; 92 parties seeded. 152 tests green.

## feat(party): worldwide registry with sync, schema v7

_2026-09-15_

Party registry goes worldwide: 92 parties across India, US, Brazil, Russia,
China and South Africa, seeded from the representation tier of each country's
Wikipedia list (CC BY-SA, attribution with the other datasets). PartyEntry
gains country; recognition records the tier that earned the row.

Schema v7 adds `parties` (slug PK, country/name/aliases/stronghold/
recognition/updated_at) and `party_sources` (per-country URL + last-sync
clock). The extractor now reads the table with a bundled fallback, so matching
never goes dark on a fresh install racing the seeder.

Sync keeps it: per-country jsoup parsers behind one interface, a PartySyncer
that unions aliases on update, preserves hand strongholds, inserts unknown
slugs and never advances the clock on an empty parse, a yearly WorkManager job
plus the Sources-screen manual path running the same code.

Alias calls made along the way, each pinned by a test: bare Congress is gone
(it means the US legislature), Good never matches, ATM means cash machine,
Forward and Forward Party stay apart via longest-match.

Verified on device, not just in tests: v7 live, 92 parties / 6 clocks seeded,
no crash, Home renders. 152 tests green, lint clean.

Also records ADR 0002 (rules slice: universal source spec, gnews-search first).

## fix(feed): drop ET top seed, its feed is 92% stale filler

_2026-09-15_

Reverts the ET half of the previous commit. Measuring its default feed: 6 of 76
items current, 70 stale evergreen filler with old pubDates. Seeding it would buy
a handful of live headlines plus a standing pile of junk rows per sync. HT India
and Mint Money stay — both verified fresh-windowed.

Lesson taken: diagnose first, seed second.

## feat(feed): seed ET top, HT India, Mint Money

_2026-09-15_

Closes the bank-bandh coverage gap: the strike lived in outlets and sections no
seed watched (ET and HT unseeded; Mint's seed was markets-only while the story
ran in money). Three seeds, all verified HTTP 200 keyless before adding:

- et-top / Economic Times, news.
- hindustantimes-india / Hindustan Times, news.
- mint-money / Mint Money, news + finance, like the markets desk.

Caveats recorded, not solved: ET's default feed mixes current items with stale
evergreen filler carrying old pubDates — those sink by timestamp and dedupe
eats repeats, so the cost is junk rows, not wrong rows. And a fast top feed
still turns over in ~24h with no pagination (Hindu), so an hourly sync can miss
a story on a heavy news day; Express is the only seed with ?paged=2.

Verified: 132 tests green, lint clean. No install.

## feat(ui): show place and party mentions in the meta line

_2026-09-15_

The stored mentions layer is now visible. "From AAP to BJP to Congress…" read
with a bare source label while its neighbours carried chips; it now reads
`— SOURCE · AAP · BJP · +1`. Wired through all three TagLine consumers (News,
Weather, Article), not just the reported screen.

Decisions, all the designer's: mentions render as quiet micro-caps text, the
same layer as natures — only subjects get a fill, and a box would read as a
white chip against the paper. Order is subjects, then mentions, then the rest:
concrete who/where beats the abstract kind. Mentions share the 2-slot clamp
instead of extending it; overflow folds into the existing +N, row heights
untouched, and a mention duplicating a tag spends no slot. Confidence below 0.8
is hidden in the UI (drops the noisy alternate-name guesses, keeps canonical
places and parties) — the DAO returns everything, the cut lives in the UI.

Reads are batched per page (new forItems), never per-row in composition; lists
render first and mentions fill in without moving rows. No schema change.

Also fixed alongside: the gazetteer-count assertion my 1k rebuild broke
(12,600 hardcoded → a band check that survives future rebuilds).

Verified: 132 tests green (8 new TagLineMentionsTest), lint clean. No install.

## feat(geo): lower gazetteer cutoff to 1,000 people

_2026-09-15_

POP_MIN 10,000 kept 4,701 towns and dropped 541,433 rows, which felt like
throwing away too much — but counting the dump showed the bulk is sub-1k
villages. At 1,000 the gazetteer keeps ~6,740 towns: 14,639 places for 0.47 MB,
only ~60 KB more than before.

Rebuilt places.dat from the same GeoNames inputs with the same script; the
importer reads whatever the asset contains, so no code changes. Importer tests
still parse the real asset green.

## feat(mention): places and parties as stored mentions, schema v6

_2026-09-15_

New `mentions` layer: every item keeps its entity mentions (places, parties)
beside its tags, in a v5 -> v6 migration that only adds tables. Stored, not yet
displayed — no UI, tile or tag changes.

- `places` table seeded from the bundled gazetteer on launch (count-gated,
  batched, never throws into startup); `mentions(item_id, kind, surface,
  entity_id?, confidence)` is append-only and idempotent, mirroring `item_tags`.
- MentionKind is free TEXT (`place`, `party`), so future kinds need no migration.
- Extraction is dictionary matching with longest-match-wins: place surfaces from
  name + ascii + alternates (Devanagari included, via Unicode lookaround bounds),
  parties from a bundled 29-party source with stronghold states. Aliases under 3
  chars are excluded (SP/NC stay inert until entity resolution exists).
- Wired into both ingest paths after the tag writer, plus a cursor backfill at
  launch for existing items. Re-runs are safe: IGNORE + a NOT IN walk.

Verified: 124 tests green (12 new: extractor + importer + schema), lint clean.

## fix(ui): source labels use the catalog name, not the feed id

_2026-09-15_

The News list read "THEHINDU TOP" because three call sites (News, Weather,
Article) each derived the label from the feed id with their own copy of
`removePrefix("rss:").replace('-', ' ')`, ignoring the `name` FeedSource already
carries. One name per source, derived once: `sourceDisplayName()` in
CommonComponents resolves `rss:<id>` through FeedCatalog and falls back to a
de-slugged id for anything unknown, so a label is never blank.

SMS rows store the bare source "sms"; that label is byte-for-byte unchanged.

4 regression tests; 112 tests green. Compile + lint only, no device install.

## chore: keep commit subjects short, notes in CHANGELOG.md

_2026-09-14_

Commit messages had grown into essays — the last two ran to 30 lines — because the
reasoning, the trade-off and the verification had nowhere else to live. They now
live in `CHANGELOG.md`, keyed by subject, and the commit itself keeps one line.

Write it like this:

    feat(tag): games vocabulary as data

    <!-- changelog -->
    Adds a `games` tag, and moves the vocabulary out of the tagger into a
    TermSource.

Everything from the marker on is moved into `CHANGELOG.md` and committed with the
change; a message with no marker is left alone.

Mechanically this needs *two* hooks, and establishing that was most of the work:

- Git writes the commit tree **before** `prepare-commit-msg` and `commit-msg` run,
  so a `git add` in either is too late — the changelog landed in the *next* commit
  instead. Confirmed by probe in a scratch repo, after the first attempt made
  exactly that mistake.
- `pre-commit` *can* change what a commit contains, but the message does not exist
  yet when it runs.

So `commit-msg` stashes the note and `post-commit` writes `CHANGELOG.md`, stages it
and amends the commit. The amend is guarded by an env var, because `--no-verify`
does not suppress `post-commit` and it would otherwise recurse.

The eight existing long messages are backfilled into `CHANGELOG.md`, marked as
pre-hook.

Verified in a scratch repo before installing: the changelog lands in the same
commit; two notes keep newest-first order with each commit carrying only its own
entry; a markerless commit is untouched; a marker with an empty note is a no-op
rather than an awk crash (which is what it did first); and four commits stayed four
commits, so no recursion.

Enable once per clone: `git config core.hooksPath .githooks`.


## feat(tag): games vocabulary, with vocabulary as data rather than code

_2026-09-14_ · _recovered from the pre-hook commit message_

Adds a `games` tag - cricket, football, hockey, tennis, kabaddi, MotoGP, F1,
olympics, IPL/ISL/PKL, leagues, athletics - and moves the vocabulary out of the
tagger into a TermSource. This is the second hand-curated vocabulary after
`festival`; with only one the seam would have been speculative, with two it pays
for itself.

Adding a term is now a source, not an edit to HeuristicTagger plus a version bump
plus a full re-tag (CODE-DESIGN-GUIDELINES §2).

- TermSource / BundledTermSource / TermStore. Sources are merged and alternatives
  are **unioned per tag**, so a user- or remotely-supplied vocabulary extends the
  bundled one rather than replacing it. Compilation is memoised, rebuilt only on
  invalidate() - which is what a future dynamic source needs.
- Tags.GAMES is a *subject*, so the promotional-DLT gate applies: "Cricket Bat
  Sale" from a `-P` sender is promo, not games.
- HeuristicTagger v7; the lexicon is injected, and the default keeps existing call
  sites and tests working.

The test suite caught a real gap: the list had `formula_1` but not the bare `f1`
that was actually asked for.

Verified on device, not just in tests: v7 is active; `festival` 28 and `games` 21.
All 7 games-tagged SMS genuinely contain "cricket" in the body (JioHotstar offers,
`-S` senders, so the gate correctly left them alone), and the other 14 are news
items matching tournament/football/hockey/olympic/tennis/world-cup/archery.


## feat(feed): OPML import, and a CDATA-aware XML sanitiser

_2026-09-14_ · _recovered from the pre-hook commit message_

OpmlParser: a minimal dependency-free reader (JDK DOM, pure, unit-tested).
`group` comes from the enclosing outline - a country for the India file, an
interest for the recommended set.

Bundles awesome-rss-feeds India.opml (36 feeds, CC0) as **data** rather than
hardcoding them, per CODE-DESIGN-GUIDELINES §2. Provenance and the reason some
of its URLs are known-dead live in assets/feeds/PROVENANCE.txt.

FeedParser.sanitizeEntities now escapes bare ampersands in addition to resolving
undeclared entities, and skips both inside CDATA.

The CDATA part is the important one. The first version of this fix ran over the
whole document, which would have rewritten `&` inside CDATA - where it is
literal - pushing a visible "&amp;" into the rendered summary of three of the
seven live feeds. JsoupFeedProbeTest (included) is what caught it.

Verified against real data, not fixtures: the bundled OPML parsed as **zero**
feeds before this change and 36 after, and OpmlParserTest asserts that count
against the shipped asset, so replacing the file fails loudly rather than
silently importing nothing.

Note: no live feed was broken before this. A bare-`&` scan reported three feeds
returning nothing, but that was a bad diagnostic - it counted `&` inside CDATA,
where it is legal. All seven parse correctly either way.


## docs: park the worldwide gazetteer in ARCHITECTURE §12

_2026-09-14_ · _recovered from the pre-hook commit message_

`places.dat` is India-only — built from GeoNames IN.zip — so a story about Sri
Lanka, Nepal or the Gulf resolves to no place at all. That is a ceiling, not a
bug, and it is now written down with the source options rather than remembered.

Records the three routes: GeoNames cities15000/5000/1000 (worldwide, CC BY 4.0,
same columns and same builder — a bigger input and a looser filter, not new
code), allCountries.zip (everything, ~2 GB, filter hard), and Geoapify's
localities share.

Geoapify is flagged unverified on purpose: the index page lists ~245 per-country
zips with no bulk download, and states neither the internal format nor the
licence. `readme.html` and one archive would have to be inspected before it could
be trusted.


## chore(app): build arm64-v8a only

_2026-09-14_ · _recovered from the pre-hook commit message_

One personal phone (Nothing Phone 2a, arm64-v8a). The only native library in the
tree is androidx.graphics.path at ~10 KB per ABI, so this removes ~27 KB today —
recorded honestly rather than dressed up as an optimisation. It is here so a
future native dependency cannot quietly add four ABIs and multiply it.

Verified the APK now contains only lib/arm64-v8a/.


## fix(geo): ship the gazetteer as .dat and make the bundle reproducible

_2026-09-14_ · _recovered from the pre-hook commit message_

aapt decompresses any asset ending in `.gz` at package time and strips the
extension, so `places.tsv.gz` shipped as an uncompressed 1.05 MB `places.tsv`.
Two consequences, both silent:

- a runtime lookup of "places.tsv.gz" fails with asset-not-found;
- the APK carries the uncompressed form instead of the 0.41 MB gzipped one.

Renaming to `places.dat` keeps the payload intact at 406,787 bytes, which is
byte-for-byte what is committed. `noCompress += "gz"` was tried first and does
not apply — it controls APK zip compression, not aapt's asset decompression.

Also adds `tools/build_places.py`. The bundle was previously produced by a
throwaway script, so the transform existed only in prose; it is now re-runnable,
with `mtime=0` so a rebuild is byte-identical rather than merely equivalent.
Regenerating reproduces the committed content exactly (md5 f5deff21...).


## feat(geo): bundle the India gazetteer (12,600 places, 0.41 MB gzipped)

_2026-09-14_ · _recovered from the pre-hook commit message_

GeoNames (CC BY 4.0) subset for resolving article and SMS text to a place:
36 states/UTs, 763 districts, 6,891 sub-districts, 207 admin seats and 4,701
towns (population >= 10,000), with coordinates and alternate names in 13
languages.

Verified before landing, rather than trusting the earlier size estimate:

- the disambiguation case works. Berhampore (West Bengal, 24.05N 88.24E) and
  Berhampur (Odisha, 21.49N 86.63E) are distinct nodes ~470 km apart, separable
  by state and coordinates - matching on the bare English name would misfile
  stories, which is why the research called this mandatory.
- all 36 states are findable by their common English name.
- 23 "State of ..." / "Union Territory of ..." prefixes are stripped, with the
  long form kept as an alternate. GeoNames writes "State of Telangana"; without
  this, "floods in Telangana" matches nothing and fails silently as "the article
  mentioned no place".
- match against the ASCII column, not the display name: 2,130 of 12,600 names
  carry diacritics (Telangana, Saran).

The bundle is data only. The Room table, the importer and the place resolver are
not built yet, so nothing reads it. Attribution, which CC BY 4.0 requires, ships
alongside it in assets/places-attribution.txt.


## chore: ignore .env.dev/.env.prod and track the .env template

_2026-09-14_ · _recovered from the pre-hook commit message_

Real keys live in `.env.prod` / `.env.dev`; `.env` is the template with the keys
present and the values empty, so it is safe to track.

This supersedes the previous inversion, which ignored `.env` and shipped a
redundant `.env.example`. The convention now matches `sms-probe/.gitignore`:
ignore the per-environment files, keep the plain one.

Note for reviewers: `.env` was never committed with real values (verified: all
three values are zero-length), and `.env.prod` remains ignored.


## feat: bootstrap Personal Radar with ingest, tagging, store and service tiles

_2026-09-14_ · _recovered from the pre-hook commit message_

Initial commit of the personal, sideloaded Android app: one on-device store fed
by many sources, with tiles as views over it. Zero recurring cost, offline-first.

- store: events with ULID identity and a stable dedupe key, append-only
  item_tags, a tagger registry; schema v3-v5 behind an exported schema baseline
  and a migration test that executes the real DDL against SQLite
- tagging: pluggable Tagger interface with a heuristic rung, source-declared
  tags, and a subject/nature/marker taxonomy; progressive re-tag walk that
  converges and is idempotent
- sources: SMS via content://sms (incremental high-water mark), 7 seeded
  RSS/Atom feeds, Open-Meteo, Frankfurter/ECB, an article-enrichment worker, and
  a present-location provider
- tiles: Home, Services, Messages, Radar, News, Weather (+ forecast reader),
  a Sources health screen, and an in-app WebView article reader; shared tile
  shell (search + context action bar + sync bar) with one RadarHeader
- fonts: bundled Noto Serif/Sans variable faces (OFL 1.1), selected per weight
  via FontVariation
- docs: architecture, ADR 0001, plans, research (India feed catalog, place
  inference signals), design + code-design guidelines, backlog
- design/: the chosen Variant A mockups

Secrets are gitignored: `.env` and `.env.prod` hold API keys, use `.env.example`.
