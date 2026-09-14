# Changelog

Long-form notes for each commit. The commit subject stays short; everything after
`<!-- changelog -->` in a commit message is moved here by `.githooks/commit-msg`,
and entries read newest-first.

Notes marked *recovered from the pre-hook commit message* predate the hook.

<!-- entries -->
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
