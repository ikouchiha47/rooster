# Changelog

Long-form notes for each commit. The commit subject stays short; everything after
`<!-- changelog -->` in a commit message is moved here by `.githooks/commit-msg`,
and entries read newest-first.

Notes marked *recovered from the pre-hook commit message* predate the hook.

<!-- entries -->
## fix(rss): list every feed, so the page is not blank

_2026-09-21_


RSS rendered UserFeedsSection, and that section had been narrowed to user rows only when Sources was deduped - correct for Sources, whose catalog status list already shows the eight bundled feeds, but fatal for RSS, which is nothing but that section. The bundled feeds therefore vanished and the page was empty.

The section now takes includeSeeded: user feeds always, bundled ones only when asked. Sources passes false, RSS passes true, and feedRows states that difference once as a pure function with tests rather than inside a composable. The header reads 'Feeds' when the bundled ones are listed, since 'My feeds' would be untrue there.

Note: the Calendars region picker is NOT on the device - the install was piped through a grep and never checked, and the phone was already unplugged, so it needs installing before it can be seen.

Verified: 573 tests, assembleDebug, ktlintCheck.

## feat(calendar): pick a region to follow

_2026-09-21_


Regions were unreachable: the adapter could sync, but nothing could tell it what to sync. Sources gains a Calendars section that writes a source of kind calendar, the same shape as a feed, so Settings stays the only writer and no region is a literal in code - japan and india/west-bengal are the same string shape.

A region is checked against the provider before it is stored: the feed must parse to at least one dated row, so a typo fails at add time instead of syncing nothing forever. Regions can be disabled or deleted, and a user row is the only kind the repository will touch.

Adds PRD 0001 (docs/prd), which fixes the scope this serves: both world dates and personal ones; timed and recurring events with custom intervals (every 6 months, every 2 years, leap-day birthdays); plans as intents with a leave optimiser that ranks windows by leave cost across two regions; peak-window inference instead of invented prices; and a named RRULE subset rather than full RFC 5545.

Verified: 569 tests, assembleDebug, ktlintCheck.

## docs: record the search design (v19 FTS5, bundled driver)

_2026-09-21_


The search feature landed inside 72ec477, which was committed under a fix subject because the same files were being edited; this records what that commit actually contained. ARCHITECTURE.md gains 11.7: why the bundled SQLite driver is required (the platform library has no FTS5, verified by grepping the pulled libsqlite.so), why the index is FTS5 external-content with the trigram tokenizer (the requirement is infix: rtel must find airtel), why sync is done with triggers rather than app code, why queries are scoped to SMS in SQL while the index stays global, and why MigrationSchemaTest must assert the virtual table and triggers itself, since Room cannot export or validate either.

It also records the trap that only showed on device: with a driver set, Room dispatches to Migration.migrate(SQLiteConnection), whose base implementation throws. A factory overriding only migrate(SupportSQLiteDatabase) passes the JVM schema test and crashes on the first real migration; Migrations.kt now overrides both.

## fix(messages): swipe and cancel in search results

_2026-09-21_


Search results rendered MessageRow directly, so a result could not be swiped while the same message could be in the stream. Results now render through SwipeableMessageRow like everything else, and both lists share one pair of toggleSave/deleteMessage helpers so an action can never apply to one list and silently miss the other - a search result is a message, not a read-only copy of the row.

Adds a cancel X to the search field: one tap drops the query, the results and the field, so leaving search never needs two. SearchField gains an optional trailing slot, so existing callers are untouched, and Glyph.Close joins the drawn set.

Verified: 563 tests, assembleDebug, ktlintCheck.

## feat(messages): delete the app's stored copy

_2026-09-21_


Swipe now reveals two actions: SAVE/UNSAVE and DELETE. DELETE removes the app's stored copy - the event plus its tags, mentions, typed fields and rule matches - and nothing else: the message on the device is untouched, and offering to remove that too (WhatsApp's delete-for-everyone) is a separate capability behind its own permission and confirmation, not a swipe. It is effectively permanent for that message because SmsSource holds a high-water mark, so it is not re-read; the one exception is resetMarkIfTableIsEmpty if the whole events table ever empties.

Dependents are deleted by item_id before the event row, the same order the schema migrations use, so a partial failure cannot orphan rows. The sidecar deletes run unconditionally because they are idempotent. Adds a drawn Trash glyph to the flat set.

Verified: 537 tests, assembleDebug, ktlintCheck.

## fix(messages): reveal-actions swipe, bookmark glyph

_2026-09-21_


The swipe did nothing: SwipeToDismissBox only fires confirmValueChange once the drag crosses its default threshold (half the row), so an ordinary swipe sprang back silently and no save was ever written - confirmed by zero rows carrying a stamp on the device. Replaced with an explicit reveal: the row slides left, stays open at a fixed reveal width, and closes only after you tap the action, so the movement is the receipt and the action is visible before you press it.

Adds a drawn Bookmark/BookmarkFilled glyph to the flat set (ribbon with a notch) instead of a plus sign: outline when it can be saved, solid when it is. Also fixes the saved marker on the row, which was using the stack glyph.

Verified: 534 tests, assembleDebug, ktlintCheck.

## feat(messages): saved tab and swipe-to-save

_2026-09-21_


Messages gains a Saved tab. The tab strip is now data (MessagesTab) rather than four literals and a when(): four tabs filter SMS by box, Saved is not a box at all (mode() == null means it reads bookmarked rows ordered by save stamp), so the screen carries no special case of its own and the strip cannot disagree with the loader.

Rows swipe left to save or unsave. The swipe acts and snaps back rather than dismissing - the item is not going away, its saved state is changing, and a row that vanished would read as a delete. Unsaving from inside the Saved view drops the row, since it no longer belongs there. A saved row also marks itself next to its state chip.

Verified: 534 tests, assembleDebug, ktlintCheck.

## feat(watchers,saved): rule-fire feed and item bookmarks

_2026-09-21_


Watchers: a fire now says what matched, not just that it fired. MatchedClauses reports the clauses that held with the stored value that satisfied them (temp_c 40.2 > 40), built from RuleWriter.itemsFor so the reason uses the same facts ingest evaluated. A narrow RuleFireDao carries the feed's two reads - putting them on EventDao/ItemRuleDao would have added two methods to six unrelated test fakes. A rule whose condition_json is unreadable now still reports its fires, without a reason, instead of silently dropping them (that was a red test). Watchers screen orders recently-fired first, never-fired last, and the Watchers/Alerts tiles now open it.

Saved: bookmarks are a stamp on the item (events.bookmarked_at, v18), not a side table - 1:0..1 rides along in every list read and needs no join. Nullable with no DEFAULT and a plain declared index: Room and MigrationSchemaTest both compare the index set, so a partial index living only in the migration would fail validation on open. Toggle rule is pure core (nextBookmark/isSaved), tested first, including that a stamp of 0 is a save. Saved reads page on the save stamp, not publish time.

Verified: 530 tests, assembleDebug, ktlintCheck.

## fix(adapters): one fetch window for weather, fx and calendar

_2026-09-21_


The store-backed adapters were written with a bare Http fetch, so they bypassed the caching the providers already had: weather and fx fetched on every refresh (hourly worker, every screen open, every sync), and the three fx pair sources each fetched the identical URL - 1 request per 6h became 3 per refresh.

CachedBody restores the window in one place: inside it a stored body is served, outside it fetches and stores, and a failed fetch serves the stale body. Weather and fx read through the providers' own cache keys and windows, so the adapters and WeatherProvider.observe() share one cached body and cannot disagree; one fx body feeds all three pairs. Calendar (new, no provider) uses the same window at a monthly cadence.

Removes the half-applied defaultIntervalSec abstraction: fetch frequency belongs to the fetch, not a second mechanism in the dispatcher. Adds FetchRateProofTest, which failed before this change (2 fetches for two ingests 1ms apart; 3 for three pair sources) and now asserts 1 and 1.

Verified: 499 tests, assembleDebug, ktlintCheck.

## feat(calendar): ics reader and calendar storage

_2026-09-21_


Schema v17 adds calendar_dates keyed by feed uid, indexed by (region, starts_at) for next-N-days reads. The ICS reader is pure core/calendar: unfolds continuation lines, unescapes text, reads DTSTART/SUMMARY/UID, and derives national vs regional scope from the uid. Verified against the OfficeHolidays India and West Bengal feeds: Nager.Date returns 204 for India and the date-holidays dataset carries only five fixed Indian dates, so the feed adapter is the source. Travel UI and the fetch adapter come next.

## feat(ui): compact counts, scrollable tabs, deduped sources

_2026-09-20_


Counts above a thousand read as 1.50K/2.40M in headers, tabs and glances. TabStrip scrolls horizontally and keeps the selected tab in view, so nine tabs fit. Sources gains a per-kind Sync activity section from sync_runs, lists only user feeds (seeded rows are the catalog feed list above), and folds By tag/By source under one Inventory header.

## feat(radar): sync activity log with Events tab

_2026-09-19_


Schema v16 adds sync_runs: one row per source per poll with outcome, item count and error, pruned to 30 days. Feed, SMS and gauge ingest all record runs through one SyncRecorder seam. Radar gains an Events tab reading latest-run-per-source with per-source names, while All and its counts exclude observations. Home Events-24h stat navigates to the Events tab via a tab preselect route.

## fix(tiles): sms-scoped messages, sync labels, instant news open

_2026-09-18_


Messages queries scope to source sms in every mode, so feeds and observations can never appear in the inbox. Weather, Money, News and Forecast headers show the last sync time or NOT SYNCED YET. News opens from the store first and refreshes behind it instead of blocking first paint on a full poll. Seeds all three bundled weather places.

## feat(weather): code-first condition glyphs from stored weather_code

_2026-09-18_


WeatherCondition gains Fog/Drizzle/Showers/Snow bands driven by the stored WMO weather_code, with rain-chance bands as fallback when no code exists. WeatherDay carries the code from both the provider parse and the store week read; the strip cell shows the code's glyph and label. Tint groups stay in-palette with the grouping asserted explicitly.

## feat(weather,fx): store-backed forecast week and FX everywhere

_2026-09-18_


Wide observation set: current rows carry temp_c, rain_mm, humidity_pct, wind_kmh and weather_code; each forecast day is a keyed weather:<slug>:fc:<date> row with temp_max_c, temp_min_c, rain_chance and weather_code. One fetch writes current plus the 7-day week. FX derives all pairs from one USD-base reply via crosses and seeds eur/gbp alongside usd. Weather week, Forecast detail, Money cells, Home glance and Radar glance all read ObservationRepository with honest placeholders; SYNC runs the gauge adapters. SMS/RSS/Topics fetch, tagging, item rules and condition parsing unchanged.

## feat(catalog): observations, adapters, series dispatch and store-backed tiles

_2026-09-17_


Completes ADR 0005 T6-T20. Schema v15 adds kinds/facets/kind_facets with add-only CatalogSeeder and CatalogStore (instance-gated). kinds are strings: SourceSpec opens up with sms/weather/fx/device specs, KindAdapter registry owns parse and identity (Topics stays gnews:slug, unbound kinds skip), SourceSeeder gains sms/weather/fx/device rows. Gauges write observations upsert-by-bucket with gauge-only field replace; counters keep IGNORE freeze. SeriesEvaluator gains timestamped points with retention/gap/boundary honesty; RuleWriter dispatches item vs series and dry-runs series; previewer previews series drafts. DraftCompiler plus ConditionStringify make authoring catalog-driven; rule-seed guard checks the catalog. Weather strip and FX cells read ObservationRepository with honest placeholders; enricher skips observations. Fetch and tagging paths unchanged.

## feat(catalog): facet catalog and typed measures core

_2026-09-17_


Adds pure core/catalog: Facet/Kind/Catalog, InMemoryCatalog union and fail-closed seeds, OpMatrix type x measure with reserved ops loud-fail, FacetCompiler to frozen Condition leaves, CatalogSeeds day-one kinds, and pure core/rules SeriesEvaluator with edge-not-level crossing. UI untouched; fetch/tag paths unchanged.

## docs(adr): kinds, instances and declared keys — and why services are not providers

_2026-09-17_

Records the shape agreed while settling how a rule picks a source.

A source has a kind, and most kinds have instances: an rss source is a feed, a
weather source is a place, an fx source is a pair. Instances are rows in sources with
kind and spec_json, which is already how the table works, so the authoring UI is
two-level - pick the kind, then the instance - and a rule may name several instances,
several kinds, or none at all. Empty means every source, which is what makes a
cross-source rule possible.

A kind declares its keys. That is what makes the builder honest rather than
permissive: the field picker offers the keys of the kinds actually selected, so temp_c
appears because a weather source is in play, and a field no selected kind supplies is
never offered. FieldNames.SUPPLIED is a global {sender, amount} today, which is
precisely why the builder cannot suggest a temperature - it has no idea weather
exists.

HistoryProvider is not a separate concept; it is the series shape from section 2.
Observations are canonical items with their keys in item_fields (v12 exists for this)
and a place as a mention, so a monitor's window is just stored rows, as section 8
already requires.

Services are not providers and must not become them: a tile is a view plus config and
can aggregate several sources, while a provider produces one stream. But every service
whose numbers come from outside must be backed by a source, or a temp > 40 rule is not
unimplemented but impossible and no monitor can ever watch a rate. Consequence for
Weather and M&M: their providers render a cache and write no events; they become
series sources and the tiles read the store - with an empty state that says so, since
a tile whose source has not fetched yet must not render blank.

## feat(sms): poll for new messages in the background, so classification stops waiting for the app

_2026-09-17_

No worker touched SMS. The only thing that ever read the provider was the observer
MainActivity started on ON_START and stopped on ON_STOP, so SMS was ingested only
while the app was in the foreground. Since tagging runs at ingest and SMS is the
majority of the store - about 2,500 of 4,900 items - Promo, Expense and Incident
classification of text messages waited for the owner to open the app. Feeds never had
that problem; the feed worker polls them hourly.

There is now a periodic SmsSyncWorker, deliberately a poll rather than a broadcast: a
manifest SMS_RECEIVED receiver is gated behind being the default SMS app on modern
Android, whereas reading the provider is already permitted and already implemented.

The worker and the observer share one suspend entry point, and that sharing is the
point - two implementations would drift, and the observer path already had the
high-water logic. A poll reads only rows newer than the persisted lastSeenId and
advances it afterwards, so it cannot re-ingest the inbox; the store keeps sms:<id> as
a second guard.

No network constraint, which looks like an omission beside the feed worker and is not:
reading the provider and writing to Room is entirely local, so requiring CONNECTED
would make a catch-up fail on a train for no reason. The comment says so.

The honest limits are in the code too: WorkManager's periodic floor is fifteen
minutes and Doze may defer it further, so this is within about fifteen minutes best
effort rather than instant, and with the app force-stopped nothing runs until the
next launch. The observer stays for immediacy while the app is open.

Getting there required taking Context and AppDatabase out of SmsSource in favour of a
SmsReader and a SmsSyncMark, which is why the worker is testable on the JVM at all,
and why MainActivity now takes the source from the composition root instead of
building its own - the observer and the worker are one object graph.

Verified: 416 tests, 0 failures (9 new). The fifteen-minute floor and the absent
network constraint are asserted on the built work request. Not verified on a device:
real Doze deferral and force-stop behaviour are reasoned, not observed.

## feat(rules): a preview hit carries the amount and the place, so rows can show what matched

_2026-09-16_

The dry-run mockup shows each matching event as a row with its amount and its place
- "Sample Bank debit 12,480" and "Indiranagar" underneath. The preview could not
render that: RulePreviewHit carried only id, source, title and timestamp, while the
amount lives in item_fields and the place in mentions. The data was modelled and
stored; it was simply never projected.

A hit now carries the item's typed fields and its place surface. It carries the whole
typed-field map rather than a named amount and sender, so a new producer field needs
no change here and nothing speculative is added.

The important part is what the read is bounded by. The evaluator must see every
candidate - that is the scan cap, 500 - but extras are only needed for the handful
the UI renders, so the field and mention read is issued for the sampled ids alone. A
test runs the preview over 5 and then 50 candidates with a sample limit of 2 and
asserts the extras read is the same two ids both times, which is the check that
stops a ten-times-larger candidate set from costing ten times as much. The obvious
implementation gets this wrong, so it is asserted rather than commented.

The extras read reuses the same batched DAO shapes materialise already uses, so there
is still exactly one way to read fields and mentions. The preview continues to write
nothing, and the existing snapshot assertion that proves it stays green.

Deliberately not built: the mockup's "would have fired 4 alerts / 2 grouped by
merchant" line, which the UI can derive from the match count and the action, and the
threshold what-if, which is a different feature.

Verified: 410 tests, 0 failures. Not verified: on a device - the UI does not render
the new extras yet; that is the next lane.

## feat(rules): a rule owns its colour, so it is not green for being about finance

_2026-09-16_

The rules list took each rule's colour from its tag - Tags.FINANCE mapped to Teal and
so on - so a rule about finance was green because of its subject, and two rules about
the same subject were indistinguishable. Colour is now a property of the rule.

Migration v14 adds a nullable color column, and RuleColor.requireValid is the single
owner of the accepted form: #RRGGBB, no alpha, no shorthand, no named tokens. That is
the narrowest form that round-trips straight from the theme palette into a picker,
and it avoids baking presentation choices into storage before a surface needs them.
It is called by create, update and the seed reconcile, so the three cannot disagree,
and a bad value is rejected before any write with a message naming the value.

The five bundled rules carry distinct explicit colours - Finance is deliberately
Indigo rather than the Teal the old tag map gave it, which is the point. Existing
installs keep color = NULL on already-seeded rows, because the reconcile is add-only
and a row that exists is never updated; the migration comment records that, and NULL
is a valid state meaning none chosen rather than a broken one.

A trap worth passing to the picker lane, recorded in the report: color defaults to
null, so the existing four-argument update call compiles but clears the colour on
every save. The picker must pass the row's current colour back, the way it already
does for name and condition.

Verified: 407 tests, 0 failures. Not verified: the migration on a real device DB.

## docs(plan): a text-only condition renders no summary on the rules list

_2026-09-16_

Found by dumping the live screen after the UI merged. Bandh and strike watch, whose
condition is a bare text predicate, renders its name and its delivery line but no
condition line - while the mention rule, the all-of-two rule and the nested
any-inside-all rule all render theirs. The lowercase pattern appears nowhere in the
dump, so it is not truncation.

ConditionSummaryTest asserts the formatter returns text(any) ~ bandh for exactly that
shape, so the gap is between the summary function and how the row renders it, not in
the formatter or the parser. It needs a test at the row level, which is the layer
with none today.

Correcting my own first report: I said two rows were missing their summary. Only one
is. My dump extraction capped captured text at 60 characters and silently dropped the
78-character finance summary, so the measurement error was mine rather than the UI's -
the same class of mistake as the feed:rule-status false negative earlier, and worth
noting twice in one session.

## feat(ui): the rules authoring screen

_2026-09-16_

T6.3 and T6.4. A Rules tab in the bottom nav lists every rule with its condition in
words rather than raw JSON; tapping one you made opens the builder; tapping New rule
starts a fresh one. The builder offers the predicates the parser actually accepts -
subject, nature, marker, mention, source, field and text, composed with all or any -
and emits them through buildJsonObject with the exact key shapes ConditionJson and
ActionJson expect, so it cannot produce a condition the parser would reject. Preview
and save stay disabled until the draft is valid.

The live preview is the point of the screen and it is real: it calls the bounded
loader over stored history and shows "N events would match, last 7 days", with the
sample rows and a live marker, and it says "first N shown" when the scan cap cut
the set short rather than implying the count is exact.

ADR section 9 is kept visible rather than blurred: delivery is its own section with
one toggle - does this interrupt you - and position is a separate one, described as
emphasis, so the two axes are never presented as a single choice.

Bundled rules are inert by design: they carry a chip, their row does not open, they
have no edit or delete, and their toggle is not clickable, because the repository's
AND seeded = 0 guard would otherwise throw on a tap. Three deliberate departures
from the mockups, each for a reason: only the Form builder shipped, since the
mockups' Recipe mode offers toggles (\'add to timeline\', \'daily digest\') that have
no keys in the action schema and honouring them would mean emitting invalid JSON;
Rules became a sixth nav tab rather than evicting a working one; and preview hits
show source and title but not amount and place chips, because RulePreviewHit does
not carry them and the API was fixed for this lane.

Verified: 400 tests, 0 failures, ktlint clean. Not verified: any of it on a device -
the screens compose and call the real repository and previewer, but no create, edit,
delete or toggle has been tapped through on a running app.

## refactor(rules): the preview reports why, not what to display

_2026-09-16_

Found while reviewing the new rules screen's copy. RulePreview.Unavailable carried
a free-text sentence that the screen rendered verbatim, and the data layer was
writing those sentences deliberately - the KDoc said the reason "is a complete
sentence the UI states". That inverts the layering rule that formatting belongs to
the UI, and the consequence was visible: a series rule would have shown the user
"series predicates cannot be previewed until the monitor engine lands (ADR 0003
section 8)", and a failed preview would have shown a raw exception message.

Unavailable now carries a kind - INVALID_DRAFT, SERIES_UNSUPPORTED, UNKNOWN - and
the underlying cause in a detail field that is for logs only. The screen maps the
kind to its own wording, so developer-facing text cannot reach a user by
construction rather than by discipline. The test asserts the kind instead of
substring-matching the evaluator's message, which also stops it from breaking when
that message is reworded.

## docs(plan): the preview loader landed, the UI is building against it

_2026-09-16_

Records the bounded history loader as landed (3740401, merged 5e82197) rather than
in flight, and what it actually is: a date-bounded, scan-capped candidate read that
narrows in SQL only where the condition makes it cheap and indexed - source, tag,
mention - and leaves regex and typed comparisons to the evaluator, which is the
division ADR section 12 draws. A series condition comes back as
RulePreview.Unavailable rather than throwing into the screen, and the preview
writing nothing is asserted by snapshotting every table around it rather than
trusted. The SQL is tested against real SQLite, not only through fakes.

T6.3 and T6.4 are now in flight against that fixed API.

## feat(rules): a bounded preview over stored history, for the authoring UI

_2026-09-16_

The dry run could evaluate a condition but only over items handed to it, and nothing
loaded those items from the store - so the preview the mockups promise had no data
behind it. This is the SQL half of that, which ADR section 12 divides from the
evaluator deliberately: the coarse filter narrows in indexes, the regex and typed
comparisons decide in Kotlin.

The candidate read is always date-bounded on the indexed events.timestamp and capped,
newest first, so it can never walk the whole store. It narrows further where the
condition makes it cheap: source into events.source, subject and nature and marker
into item_tags_current (the active tagger's view, since the raw table is append-only
and would double-count), and a mention into its indexed (kind, surface) pair. Text and
field predicates are absent on purpose - a regex cannot be indexed, and interpolating
a user pattern into SQL is worse than slow.

A series condition degrades instead of exploding. RuleEvaluator still refuses crossing,
delta and min/max loudly, because monitors arrive in slice 5; the preview catches that
at its own boundary and returns an outcome naming the reason, so a screen shows
"cannot preview yet" rather than a crash. The evaluator is untouched.

Most importantly it writes nothing - requirement R4 - and that is asserted rather than
assumed: a test snapshots events, tags, mentions, fields and matches around a preview
and compares them, with the DAO's insert count still zero. The SQL itself is tested
against real SQLite rather than only through fakes, which is the right instinct for a
string this load-bearing.

Verified: 375 tests, 0 failures. Not verified: a fresh-clone build, and the screen
itself, which is the next lane.

## docs(plan): the rule preview needs a history loader before the UI can show one

_2026-09-16_

Found while starting the authoring UI. RuleWriter.dryRun evaluates over
caller-supplied seeds and persists nothing, which is correct as far as it goes - but
nothing loads those seeds from the store, so the mockups' "6 events would match,
last 7 days" has nothing to run on. The bounded history query had been assumed to be
slice 5's; it is a hard prerequisite for the preview.

Recorded as the first thing to build before T6.3: a date-bounded, scan-capped loader
that narrows in SQL where the condition makes it cheap and indexed (source, tag,
mention) and leaves regex and typed comparisons to the evaluator, which is the
division ADR section 12 draws. It must also degrade on a series condition rather
than throwing into the UI, since RuleEvaluator deliberately throws for those until
slice 5.

## docs(plan): the rules engine is device-verified, and the onConflict question is closed

_2026-09-16_

Records slices 7 and 8's T8.1/T8.2/T8.4 as landed, and the device verification that
ties them together: schema v13 with a matching identity hash, five rules and twelve
sources under stable ids, Cloudflare gone from both its registry row and its 51
events, a sync forcible on demand, and the engine's first real match sitting in
item_rules with one row and one distinct pair.

Closes the @Insert(onConflict) gap that had been open since slice 1, and closes it
by reading Room rather than trusting it. The generated DAO emits INSERT OR IGNORE
INTO item_rules, so combined with the composite primary key - asserted against real
SQLite and present on the device - a duplicate is a no-op and matched_at keeps its
first value. The lesson is worth keeping: when a Room annotation's behaviour
matters, the answer is in the generated Impl, not in a fake.

Also repairs a structural mess I made in this file: an edit used the Slice 7
heading as its anchor and replaced it, orphaning the T7 tasks under Slice 8.

Still open and recorded: item_fields is empty because SMS is its only producer and
no new SMS has arrived, so that path needs a real message to exercise.

## fix(data): adopt stable seed ids, and purge what Cloudflare left

_2026-09-16_

Reconciling seeds by stable id could not work on a device seeded before that
change, because those rows carry generated ULIDs that mean nothing: the stable ids
look missing, so reconciliation would insert duplicates - nine rules instead of
five, twenty-five sources instead of twelve.

The way out is that seeded rows are un-editable by construction. Both repositories
carry AND seeded = 0 in their SQL, so a user can never have changed one, which means
a seeded row holds nothing but bundled data and clearing it is lossless. This
migration deletes every seeded row from sources and rules, and the reconcilers
re-insert the bundled set with stable ids on the next launch. It is data only: 13.json
is byte-identical to 12.json except for the version, and the schema identity hash is
unchanged.

The same migration purges what the dropped Cloudflare source left behind - its ~51
events plus their tag, mention, field and match rows, dependents first so nothing is
orphaned. Deleting the seeded sources rows removes its stale registry row too, since
it is no longer in the catalog and will not be recreated. The purge names one
explicit source string rather than diffing against the catalog, which would have
swept up user sources.

On a fresh install this migration never runs - Room creates the schema directly at
v13 - and the statements would be no-ops anyway, because every table it touches is
empty at creation.

Verified: 358 tests, 0 failures. Not verified: the migration installed over the live
v12 device database, and the identity hash compared on device.

## fix(data): reconcile bundled seeds, and a debug-only force sync

_2026-09-16_

Two things that both existed to make this app verifiable.

Bundled seeds never reached an install that already had rows, because both seeders
gated on count() == 0. That is why the device held four rules while the release
shipped five. They now reconcile on every launch by a stable id, inserting only
what is missing, never updating or deleting an existing row, and leaving seeded
untouched once written. That required the ids themselves to become stable strings -
the old seeders generated a fresh Ulid per install, which nothing could ever
reconcile against. Drift is logged rather than silently applied, so a seed whose
condition changed in a later release does not overwrite a row the user may have
customised.

Consequence, stated because it bites the next install: rows seeded before this
change carry generated ULIDs, so the stable ids look missing on that device and
reconciliation would insert duplicates - nine rules instead of five, twenty-five
sources instead of twelve. A one-time adoption is needed before installing this on
the existing device, and it cannot be done by matching ids, since the whole problem
is that the old ids are meaningless.

The force sync is a debug-source-set broadcast: refresh takes a force flag that
bypasses the payload-cache gate for that call only, while every existing caller
keeps the sixty-minute gate. The receiver, worker and manifest entry live under
src/debug, so release cannot contain them at all - verified rather than assumed:
zero matches in the merged release manifest and zero ForceSync references in the
release dex, against five and twenty in debug. No BuildConfig check to forget.

Verified: 357 tests, 0 failures. Not verified: the adb trigger on a real device,
and the reconcile against real Room - both are fakes at the JVM level, as
everywhere else in this project.

## docs(plan): a seed added later never reaches an existing install

_2026-09-16_

Found by verifying schema v12 on the device rather than in tests. The migration
itself is clean: user_version is 12, item_fields exists with both indices and its
primary-key autoindex, and room_master_table.identity_hash matches 12.json exactly,
so Room structurally validated it.

But the database holds four rules, not the five RuleSeeder ships. The seeder runs
only when the table is count() == 0, so any bundled seed added after a user's first
launch is invisible forever - which is precisely why the mockups' flagship
amount > 10000 rule cannot be exercised on the device that needs it. SourceSeeder
has the same shape and the same flaw. Queued as T8.4: reconcile on launch instead,
inserting only ids that are missing, leaving existing rows and user-created rules
untouched, with seeded = 1 still immutable.

## feat(rules): store typed fields, and let SMS supply the first two

_2026-09-16_

ADR 0003 defined fields on the canonical item and asserted that monitor series
points are rows in events with their fields, but no storage was ever specified, so
a field predicate could never match: a rule like amount > 10000 evaluated to false
forever and read as a broken engine. item_fields now exists as section 13
specifies - one row per item and name, primary key on (item_id, name), an index on
(name, item_id) for monitor windows and one on (item_id) for reading an item - with
three typed columns mirroring the language's FieldValue so nothing needs widening.

Storage alone would change nothing, so SmsSource is the first producer: it already
had the sender address, and a conservative parser now extracts a transaction amount
from the message body. The parser is deliberately narrow - it requires a currency
marker and a money-movement verb, and refuses bills, promos, balances, multipliers
and anything ambiguous, because a wrong amount makes a rule fire wrongly, which is
worse than a missing one. Fields are written before rules are evaluated, so the
evaluator sees exactly what is stored.

That last point is why RuleWriter reads fields from the store in materialise rather
than taking them on the seed, exactly as it already does for tags and mentions.
Carrying them on the seed would have made two owners and allowed evaluation to
disagree with what was frozen at ingest.

RuleSeeder gains the mockups' flagship rule - source sms with amount > 10000 -
against the one producer that actually supplies an amount today, and the guard test
changes shape with it: instead of asserting the string never appears, it now
recurse-parses every shipped condition and asserts each field name is one a
producer supplies, and that at least one seed exercises one, so the guard cannot
quietly become vacuous.

Verified: 348 tests, 0 failures. Not verified: the parser against the device's real
SMS corpus, and Room's real conflict mode, which no JVM test here can exercise.

## chore(feeds): drop the Cloudflare status feed

_2026-09-16_

The owner does not care about Cloudflare, and it was never an India-relevant
signal: like the AWS feed beside it, it is a foreign cloud provider's status page.
Removed from FeedCatalog, so it stops polling and stops appearing as a source.

The pinned counts in SourceSeederTest moved with it - twelve locked rows rather
than thirteen, and eight rss seeds rather than nine - which is the test doing its
job rather than an unrelated break.

Left in place: the ~51 events it already ingested, and its seeded sources row. Those
are purged by the migration queued as T8.1, which matters beyond tidiness because
those rows are the ones carrying the future-dated ingested_at that made one of my
own earlier checks invalid. AWS is the same category and is still there; say the
word and it goes the same way.

Verified: 334 tests, 0 failures, ktlint clean.

## docs(plan): queue Cloudflare removal, a force-sync trigger, and push-driven SMS

_2026-09-16_

Three things asked for while testing, recorded so they survive the conversation.

Drop the Cloudflare incident source: status-cloudflare is a status feed the owner
does not care about. Remove it from FeedCatalog.SEEDS so it stops polling, and
purge its seeded source row plus its events and their dependent tag, mention and
match rows - which also removes the future-dated ingested_at rows that made one of
my own checks invalid.

A reliable force-sync trigger. The 60-minute payload gate makes device testing
painful and it was never faster: SyncScheduler has been 60 minutes since bootstrap
and 475d142 only renamed the constant, while FeedIngestor's payload gate was 60
minutes from its first commit. Tapping the on-screen Sync control does not refetch
and ageing provider_cache timestamps by hand does not either - both verified, not
assumed - so add a debug-only adb-triggerable trigger that bypasses the cache gate,
without weakening it in release.

Make SMS push-driven rather than polled. The owner's point is right: SMS arrival
time is not something the app knows. A ContentObserver on the SMS provider delivers
messages as they land. No ADR change is needed - section 1 makes producer internals
opaque and section 2 already calls SMS a message stream. Feeds cannot be pushed the
same way, so their honest equivalent is conditional GET plus letting app-open and
network-change trigger a refresh.

## docs(plan): materialisation is unobserved on device, and why that is starvation

_2026-09-16_

Records what slice 6.1/6.2 does and does not prove on device. It does prove the
seeder: rules holds the four seeded rules with seeded = 1, enabled = 1 and
positions 10 to 40, and ingest is still alive. It does not prove match
materialisation, because item_rules is empty and the engine has never been handed
an item.

The mechanism is verified rather than guessed: DEFAULT_REFRESH_MS is 60 minutes and
every news feed's cached payload was 34 minutes old, so a refresh call skips them
as still fresh; SYNC_GROUP_MINUTES is 60, so the periodic sync is hourly; and the
app starts on Home, not Radar, so the launch-time refresh never runs. Relaunching
therefore cannot produce a poll, and the last poll predates the install. One real
fetch - the next hourly sync, or a manual refresh once the cache is stale - should
fill item_rules for any landed item that satisfies a seed. Until that is seen, the
evaluator is not claimed to work on device.

Also records a mistake of mine, because it nearly produced a false conclusion:
several rss:status-cloudflare rows carry ingested_at about two days ahead of the
host clock, so MAX(ingested_at) is not the newest ingest and comparing against it
proves nothing. I drew exactly that wrong conclusion before checking. The
per-feed lastAttempt timestamps in prefs are the reliable signal, and why those
status rows are dated ahead is unexplained.

## feat(rules): four starter rules, and a repository that respects them

_2026-09-16_

The engine could evaluate but nothing could author, so the rules table was empty.
RuleSeeder now mirrors SourceSeeder - runs only when the table is empty, validates
every seed before inserting, marks them seeded - and RuleRepository gives the
authoring UI something to call, with the same locked-seed guard the sources
repository uses: the SQL says AND seeded = 0 and a zero-row result becomes an
IllegalStateException, so a bundled rule cannot be edited or deleted.

Four seeds, each chosen so it can actually match data that exists today: a finance
and rates watch over the two Mint feeds, a bandh and strike text watch, a Kolkata
place watch, and a news incident watch. The fourth deliberately carries
delivery = none, which is ADR section 9's surfacing-without-interrupting case.

The load-bearing constraint: no seed uses the field predicate. Typed fields have no
storage yet - item_fields lands in slice 7 - so a seed such as amount > 10000 would
evaluate to false forever and read as a broken engine rather than a missing
column. A test asserts the literal is absent from every shipped condition, so a
future seed cannot quietly reintroduce the trap. Source ids were checked against
FeedCatalog rather than invented: the mockups' illustrative SMS:BANK is not a
source this app has.

The repository validates conditions and actions through core/rules before writing,
so a malformed rule cannot be saved, but it deliberately does not require the
condition to be item-evaluable: a field or series rule saves, a field rule then
matches nothing and a series rule is refused loudly when it runs. That boundary is
documented in both classes rather than silently enforced one way or the other.

Verified: 334 tests, 0 failures. Confirmed against the device's store that every
seed has something to match - 249, 20, 103 and 81 items respectively - so seeding
makes the engine checkable rather than merely present.

## docs(adr): specify item_fields — the storage fields were assumed to have

_2026-09-16_

ADR 0003 was internally inconsistent. Section 3 defined fields as part of the
canonical item, and section 8 asserted that a monitor's price points are rows in
events with their fields - but section 13's DDL specified only sources, rules and
item_rules. No column, no table, no migration. So nothing could ever supply a field
predicate, and a rule such as amount > 10000 would have evaluated false forever
while looking like a broken engine.

This was not a registration fault. sources, rules and item_rules all exist, 11.json
is exported, and the device's room_master_table.identity_hash matches it - Room
structurally validated that migration. The gap was in the design, and it was mine
for writing the assumption without the storage.

Section 13 now specifies item_fields: one row per item and field name, primary key
on (item_id, name), indices on (name, item_id) and (item_id), and three typed
columns that mirror the language's FieldValue exactly (Num, Str, Flag) so nothing
needs widening on either side.

A JSON blob on events was the tempting alternative and is wrong here: section 8's
monitors need a series key that can be indexed, and a JSON column cannot be indexed
by key without expression indices that SQLite would still scan. Materialising
fields also matches how tags, mentions and matches already work in this store.

The plan gains slice 7 for the migration and the writer, ordered before the
authoring UI and before monitors, and records the clarification that the slices are
not a linear chain: 1-2-3 is the engine's dependency chain, 6 depends only on 3,
and 4 and 5 are separate features.

## docs(plan): slice 3 done — the engine evaluates and materialises

_2026-09-16_

Records slice 3 (947a222, merged 4ac256e): the evaluator over a RuleItem view,
RuleWriter writing matches append-only, hooks into ingest and enrichment, and a
dry run that persists nothing.

Records why the series boundary is loud rather than silent: crossing, delta and
min/max need a window slice 5 supplies, so evaluate rejects them before it
evaluates anything, and an all with a series child cannot short-circuit past it
behind a false sibling. RuleWriter then catches it per rule, so ingest survives
but an unimplemented predicate can never masquerade as no match - the Liskov rule
made structural instead of remembered.

Also records the merge conflict this threw up: main had advanced with a docs
commit while the branch was open, and the changelog hook prepends an entry in
both, so CHANGELOG.md conflicted and was resolved newest-first by hand. Any
parallel worktree will do this.

Next is slice 6: seeded rules and a RuleRepository, then the UI.

## feat(rules): evaluate rules over landed items, and materialise matches

_2026-09-16_

The engine now has all three pieces ADR 0003 describes: a source registry, a rules
table with a condition language, and something that actually evaluates.

The evaluator takes a RuleItem - id, source, title, content, tags, mentions,
typed fields - rather than a Room entity, so core/rules stays portable with no
android import and no knowledge of storage. Rules read only what is already
stored and never fetch, which is the point of ADR section 7: N rules over one
source cost no extra fetch, and no user-authored rule can hammer a host.

Series predicates are the deliberate exception, and they fail loudly. crossing,
delta and min/max read a window of stored observations that slice 5 supplies, so
the evaluator rejects them with SeriesPredicateUnsupportedException - and it walks
the whole tree before evaluating, so an all with a series child cannot short-
circuit past it behind a false sibling and quietly look like never-matches.
At the data layer RuleWriter catches it, logs, and skips just that rule, so ingest
never crashes but an unimplemented predicate can never masquerade as no match.

RuleWriter is modelled on TagWriter and writes matches append-only with
OnConflictStrategy.IGNORE, because re-evaluation is a planned operation and
REPLACE would silently move matched_at to the latest write. It is wired into
ingest at the point where rows actually landed - the rows whose insert was not
deduped - so a re-fetched item does not re-trigger evaluation, and into
ArticleEnricher so that enrichment re-evaluates only enrichment-sensitive rules
and only the items whose text actually grew.

The dry run returns matches without touching the match DAO, and a test proves it
by first showing the writer does persist, then asserting rows and insert calls are
unchanged across the dry run.

Honest limits, not hidden: the dry run evaluates caller-supplied seeds because its
bounded history query (ADR section 12) is slice 5's; typed fields are fully
supported by the evaluator but events has no column for them yet, so the data
layer supplies none until slice 4; and the IGNORE annotation still cannot be
checked by a JVM test here, so it waits for a device check with a real match.

Verified: 317 tests, 0 failures.

## docs(plan): decide authoring — seed rules, then build the UI

_2026-09-16_

Slices 1-2 built the table and the language, but nothing can create a rule, so
rules is empty and slice 3's evaluator has nothing to evaluate. Engine-first
sequencing was deliberate; the gap between the engine working and rules working
is authoring, so it becomes slice 6 rather than an open question.

Seeding comes before the UI because seeding is what makes slice 3 verifiable on
device: a real match lands in item_rules, and that is the only way to check
matched_at and the @Insert(onConflict) behaviour, which no JVM test in this
project can reach. The UI follows the design-rules mockups and goes to the
designer lane, since a rule builder is condition and action composition with a
live dry-run preview - interaction design, not mechanical implementation.

Slice 6 also records what has to exist first: a RuleRepository with the
locked-seed guard. A storage-only version was written in slice 1 and deleted as
premature; this is the version with a caller.

## docs(plan): slices 1 and 2 done, and the gap that nothing can create a rule yet

_2026-09-16_

Records slice 2 as landed (4efc8be): core/rules is now the predicate language,
with conditions over all/any, the item and series predicates, actions carrying
delivery and position, unknown-key rejection, and enrichment-sensitivity that
recurses. Also records that the tag taxonomy finally has one owner (TagGroups,
with HeuristicTagger reading it and a partition test), that WordBoundary is
shared by the gazetteers and the text predicate, and that ARCHITECTURE section
10.5 names two natures that do not exist as Tags constants - deliberately not
invented, and noted in code rather than silently added.

States the residual risk rather than hiding it: the text bound is input length
plus a StackOverflowError catch, not a time sandbox, so a catastrophic pattern
inside the bound can still burn CPU. RE2/J is not a drop-in because it has no
lookaround, which is what the boundary pattern is built from.

And records the gap that matters for what comes next: slices 1-2 built the table
and the language, but there is no seeder, no writer and no UI, so rules is empty
and the slice 3 evaluator will have nothing to evaluate. That is engine-first
sequencing by design, but it is the difference between the engine working and
rules working, and it needs a decision.

## feat(rules): the predicate language, and one owner for the tag taxonomy

_2026-09-16_

The rules engine needs to express conditions, and the tag taxonomy it refers to
existed in fragments: `Tags` in Tagger.kt, plus a private `SUBJECTS` copy inside
HeuristicTagger. One fact, one owner - so TagGroups now owns the three groups and
HeuristicTagger reads them.

The groups are derived from the tags that actually exist: marker is news, the 7
subjects are the ones already in the old SUBJECTS set, and the 5 natures are what
is left. ARCHITECTURE section 10.5 prose lists `announcement` and `maintenance`
as natures, but Tags has no such constants, so they were not invented - the
discrepancy is recorded in the KDoc instead. A test proves the three groups
partition Tags.ALL exactly once, so an ungrouped tag cannot slip through.

core/rules/ is now the pure language, free for the engine per ADR 0003: a sealed
Condition over all/any composition with the item predicates (subject, nature,
marker, mention, source, field, text) and the series predicates (crossing, delta,
min/max over a window), plus an action carrying delivery and position - the two
axes ADR 0003 section 9 separates. Parsing rejects unknown keys in conditions and
actions, so a typo fails at write time instead of silently never matching, and
isEnrichmentSensitive recurses through nested composition because that is what
ADR section 10's scoped re-evaluation keys off.

Text matching bounds its input and pattern length, uses (?u), and reuses the
Unicode boundary definition the gazetteers already had - extracted to one owner in
WordBoundary.kt, which now serves PlaceIndex and PartySource as well.

Verified: 291 tests, 0 failures (40 new). Residual risk stated rather than hidden:
the bound is input length, not a time sandbox, so a deliberately catastrophic
pattern inside the bound can still burn CPU, and matching is truncated at the
bound. RE2/J would remove the class but is not a drop-in - it has no lookaround,
which is exactly what the word-boundary pattern is built from.

## docs(research): RBI feeds, and why bank holidays have no single source

_2026-09-16_

RBI does publish official RSS - press releases, notifications, speeches, tenders
and publications - all verified live. The trap is that each feed carries only the
latest 10 items, so it is a window rather than an archive and needs polling at
least daily. Listing pages are scrapeable but the WAF returns 418 to postbacks,
so there is no deep pagination, and there is no content JSON endpoint.

Bank holidays turned out to be the harder question, and two assumptions were
wrong. Andhra Pradesh is not missing from RBI's list: RBI names cities, and AP
appears as Vijayawada, which third-party mappers drop in favour of Hyderabad.
What RBI actually omits is union territories - Puducherry, Ladakh, Lakshadweep,
A&N and DNH&DD have no office at all. The root cause is that Section 25 of the
Negotiable Instruments Act delegates holiday declaration to state governments, so
RBI compiles rather than decides and the authoritative sources are gazette PDFs.

So consistency has to be assembled: compute weekends and the 2nd/4th Saturday
rule offline, refresh HDFC's holiday page (the only verified page covering all 37
states and UTs) monthly, cross-check RBI's office matrix for the current month,
and keep a small curated table for the UTs RBI omits plus moon-sighting and
election changes. Recorded as two backlog items with the full reasoning, exact
URLs, and a verification table marking what was confirmed, blocked or absent -
including that NPCI 403s non-browsers, the RBI RTGS list is stale because
electronic transfers went 24x7 in 2020, and Tallyfy's public dataset is verifiably
wrong.

## docs(plan): correct the feed:rule-status note, and say whose error it was

_2026-09-16_

The earlier note called the empty feed:rule-status "unexplained". It is not: the
code deliberately keeps two health pipelines, statuses (feed:status) for the
seeded catalog rendered by SourcesScreen, and sourceStatuses (feed:rule-status)
for user-added sources rendered by UserFeedsSection. It is empty because there
are no user-added RSS sources for sources.mapNotNull { health[it.id] } to keep.

The mistake was mine for writing it down without reading the two write paths
first, so the correction says so rather than quietly disappearing. The key name
is legacy from when rules meant sources, and is one of the values frozen across
v11 - renaming it would silently drop user source health.

## docs(plan): record slice 1's cleanup, evidence and gaps

_2026-09-16_

Slice 1 is done end to end: 43517af (the rename and schema v11), 42a66d4 (the
ADR and plan themselves, which the code cited while untracked), 90ff28c (the
review cleanup) and 3007a79 (the architecture doc).

Corrects T1.4, which still promised a storage-only RuleRepository that the
cleanup deleted, and records what the cleanup closed: the rest of the rule to
source identifiers, the SourceKind collision behind the TagSourceKind alias
(core.tag's was a transport, not a source kind), dead and wrong
Migrations.CURRENT_VERSION, and the unasserted dflt_value trap.

Also records two things worth not losing. First, two DAO tests were deleted as
tautologies - they asserted fakes that reimplemented INSERT OR IGNORE, so they
would have passed with the primary key removed; the real check already exists in
MigrationSchemaTest against SQLite. Second, the honest gap that leaves:
Room's @Insert(onConflict) mode is not verified anywhere, and a REPLACE would
silently rewrite match history, so confirm it on device when slice 3 first
writes matches.

Device evidence for 90ff28c included: the frozen prefs keys held (all four
rule:<id> body caches intact, key sets identical before and after), with a note
that PrefsStringCache splits each logical key into .at and .value entries, which
makes a naive substring check report a false negative.

## docs: point the architecture at ADR 0003 for rules

_2026-09-16_

ARCHITECTURE.md is binding reading for agents, and it still described the world
before ADR 0003: rules as tile-scoped, and a `rules` table holding tile scope,
condition, delivery, position and builtin.

Corrects the core model (sources fetch, rules never do; delivery is the
interruption and surfacing is the read; a rule is not tile-scoped), the data
model rows for `sources` and `rules` to what schema v11 actually created plus
`item_rules`, the Radar tab line, and the now-settled "timeline model" item in
section 7. Section 10.4 now says plainly that it describes the tagger's rules,
not the user-facing rules engine - two different things that had one name.

## refactor(data): finish the sources rename, and drop tests that tested fakes

_2026-09-16_

Follow-up to 43517af, from a review of it. The migration was sound; the problems
were honesty debt, two premature classes, and one name collision worth fixing at
the root rather than papering over.

The rename left identifiers holding a SourceEntity still called `rule`, which
became actively wrong the moment RuleEntity existed as a distinct type. Those are
now `source` throughout - FeedIngestor, the UserFeedsSection composable API and
its callers, and the tests. The persisted string *values* are deliberately kept
and now say so: `"rule:$id"` and `"feed:rule-status"` are frozen, because
renaming them would drop cached article bodies and reset every status dot.

The TagSourceKind alias is gone, and so is the collision behind it: core.tag's
SourceKind was never a source kind, it was where the text came from (SMS/RSS/
JSON/WEB), so it is now Transport and FeedIngestor uses both names plainly.

Deleted rather than kept: RuleRepository (no caller, and slice 3 names RuleWriter
for writing matches, so its job was undefined) and Migrations.CURRENT_VERSION
(unused, and wrong by its own comment - keys are target versions, so the newest
migratable version is max(keys), which disagreed with AppDatabase.version even
before this slice). A test now pins that invariant instead.

Two tests written for the rule DAOs were deleted as tautologies. They defined
fakes that reimplemented INSERT OR IGNORE in Kotlin and then asserted the fakes,
so they would have kept passing if the composite primary key were removed or
onConflict changed to REPLACE. The fact they pretended to check is already
checked for real: MigrationSchemaTest compares every exported table's primary
key against PRAGMA table_info over SQLite, and now also compares dflt_value, so
the DEFAULT trap that half the migration comments warn about is asserted rather
than described.

Also corrected stale wording: SourceRepository promised a rule engine, a
brand-new SourceEntity said "user rules", Sql.kt described the pre-v11 table in
the present tense, SourceSeeder hardcoded "rss"/"search" where the enum owns
the closed set, and SourceSpecs cited an ADR section that does not exist.

Verified: 251 tests green, ktlint clean. Section 8's DEFAULT check and the
composite key it relies on are asserted against real SQLite; Room's
@Insert(onConflict) mode itself is not verifiable at JVM level and is left as a
device check when slice 3 first writes matches.

## docs(adr): the sources, items and rules model, and the plan that lands it

_2026-09-16_

ADR 0003 settles what a source is, what an item is, and what a rule is - the
design that `43517af` began implementing. It supersedes ADR 0002, whose table
called `rules` was really a source registry.

The load-bearing decisions: producers are an interface with opaque internals, so
statefulness and non-determinism (cookies, regex, ML) are absorbed at ingest and
values are frozen into items; a canonical item is a JSON Feed 1.1 subset plus a
typed `fields` bag; identity is source-declared or a per-transport hash and never
content-derived; rules never fetch, they are pure evaluators over stored items;
delivery is the interruption while surfacing is a read-time projection, so Radar
is a query rather than a destination; and a match is stored once in `item_rules`
and read wherever a view asks for it.

Also adds plan 0002, which carries the EARS requirements (43 of them, traceable
by id from every task), the five slices, the verification bar, and a RALPH-style
loop header so the work survives context loss - the repo has lost work before,
and `docs/plans/0001` was the only precedent.

Both files were untracked while being cited by the code and the schema; this
commit puts the design of record in git.

## refactor(data): the rules table becomes sources, and rules becomes real

_2026-09-16_

The table called `rules` was a source registry - 13 seeded rows of fetch specs,
with no condition, action or delivery anywhere in it. It is renamed `sources`,
and `rules` becomes the real thing: condition_json, action_json, position.
Matches get their own append-only table, `item_rules`, modelled on `item_tags`,
so a match is written once and read wherever a view asks for it.

The rename is thorough rather than table-only. `RuleEntity` could not mean both
a source and a rule, and a `SourceEntity` holding a `RuleKind` would be a lie,
so the concept is renamed through the data layer (`SourceEntity`, `SourceDao`,
`SourceRepository`, `SourceSeeder`) and the portable core moves from
`core/rules/` to `core/sources/` (`SourceKind`, `SourceSpecs`, `SourceKeys`).
That leaves `core/rules/` free for the evaluator, which is where the ADR puts it.

No behaviour change: same seeds, same locked-seed enforcement, same ingest path,
no evaluator and no rule matching yet. Condition/action validation arrives with
the predicate language, not here.

Verified: 250 tests green. The +1 is the new v10 to v11 case, which seeds a row
into the old `rules` table, migrates, and asserts it survives the rename with
`updated_at` and `interval_sec` intact, plus both `item_rules` indexes. The
schema was exported as 11.json and the five `DEFAULT` grep hits are all doc
comments explaining why defaults are deliberately omitted.

## design: off-theme font options, and the mockups actually parse again

_2026-09-16_

The mockup stylesheets had been broken since the font switcher landed: the
:root block never closed, so every rule after it - including all fifteen
font-combination blocks - nested inside :root and the sheet parsed to 3 rules
instead of 234. Nothing could ever change. One brace fixes it, verified
headlessly: 234 rules parse, all fifteen selectors present, and the computed
font actually follows the attribute (noto -> Noto Serif, mono-all -> JetBrains
Mono, poster -> Anton/Archivo).

Also adds six deliberately incongruent options in a second group - mono
everything, Space Grotesk + Space Mono, HUD (Chakra Petch/Rajdhani/Share Tech
Mono), Corporate (Roboto Slab/Roboto), Poster (Anton/Archivo) and Soft
(Nunito) - and the switcher now injects ONE control, on the viewer only.

Note: headless Chrome does not fetch the webfonts, so that render check proves
the cascade, not the glyphs. Judging the faces needs a browser with network.

## feat(theme): IBM Plex and Space Grotesk, chosen in Settings

_2026-09-16_

Noto is out. Two combinations ship and Settings picks between them: IBM Plex
(Serif heads, Sans body, Mono figures) as the default, and Space Grotesk
(one sans for heads and body, Space Mono for figures). Font payload drops from
3.9 MB to 1.6 MB: Plex Serif/Mono and Space Mono ship as static cuts, Plex Sans
and Space Grotesk as the variable files, expanded per weight through the same
FontVariation technique the Noto build used.

The swap is live - no restart. One owner (RadarFonts.theme) holds the choice;
RadarFonts' families and every RadarType style became getters over it, so
reading a style in composition tracks the state and dozens of call sites keep
working unchanged. The choice persists through the prefs store, is loaded
before first composition, and an unknown stored value falls back to the default
rather than breaking startup.

New Settings screen (dense newspaper language: header band, section rule,
tappable rows with an IN USE marker, footer note) reached from both the Services
and Home grids - the Home tile still pointed at the placeholder, which is why
tapping Settings from Home showed a blank screen.

Verified on device: both themes render, the swap is instant, and the stored
value survives a cold start (theme.value = space_grotesk on disk). 249 tests
green.

## design: font switcher in the mockups

_2026-09-15_

The mockups can now be judged on type instead of argued about: a Fonts
dropdown switches the pairing live, wired as one data-attribute on <html> with
every stack declared in the stylesheet, so a combination is a data change and
never per-page JS. The choice persists in localStorage and is applied before
paint, so moving between pages does not flash the default.

Options are two-family pairings (one serif carrying display and headlines, one
sans for body and micro caps) after the first experiment proved three serifs
compete: Source Serif 4 + Inter, Newsreader + Inter, Libre Franklin + Libre
Baskerville, Archivo Narrow + Spectral + Inter, Literata + Public Sans,
Playfair Display + Lora + Inter, IBM Plex Serif/Sans/Mono, Fraunces + Inter,
with the app's shipped Noto pairing as the default baseline.

Fonts load lazily per selection - only the baseline is in the static import -
so the first view stays light instead of pulling fifteen families.

## fix(ui): header colour fills the status bar, taller masthead

_2026-09-15_

Every screen started below the status bar: the root column carried
systemBarsPadding(), so the strip above was the window background - a white
band over the Weather header. Headers now own that strip: each draws its fill
full-bleed to the window top and insets only its inner content row by the
status-bar height, so text never sits under the cutout. The rule lives in the
shared RadarHeader/RadarAppBar, plus the two bespoke screens (Sources,
Placeholder); no per-screen inset code elsewhere. The root keeps the
navigation-bar inset, so the tab bar still clears the gesture bar.

Status-bar icons follow the fill, using the same 0.4 luminance threshold as
headerContentColor so icons and title always agree: every header in use today
is dark (Indigo/Teal/Cyan) and takes light icons; Mustard/Chartreuse are
already covered if one is ever used. Masthead padding 8dp -> 12dp: the title
is the largest type in the ramp and measured barely taller than a service tile
at 8dp.

Verified on device: Indigo bleeds to the top with light icons, taller header,
list rows unchanged. 240 tests green.

## design: type experiment in the mockups

_2026-09-15_

Throwaway prototype, not app code: the mockups try Newsreader (headlines),
Crimson Pro (byline/lede accents), Fraunces (masthead and section titles) and
Manrope (body/labels), loaded from Google Fonts via one @import per stylesheet
and wired through the existing type tokens so the role split lives in one place.

Section headers also get the dotted treatment tried out — a dotted underline in
the section's own accent plus a dotted dot-leader across the gap — and a
stronger band (#EFEADB -> #E4DCC7) with the count stepping up from ink-3 to
ink-2, so the header reads as a band rather than a wash. Existing hues and
their meanings untouched.

## docs: worktree rule — commit before removing, never force

_2026-09-15_

Records the workflow that would have saved the RSS feature: self-contained
feature work goes in a worktree, it is committed INSIDE that worktree before
anything else, and removal is never forced. A worktree with uncommitted work
is unrecoverable on remove — no reflog, no stash.

Also records two things learned the hard way: verify a slice from a fresh
directory (the main tree can pass on files that were never committed), and
Room's KSP step has been seen to fail in fresh checkout directories, which is
the toolchain rather than the code.

## feat(rss): feed manager with verify-then-add, schema v10

_2026-09-15_

The RSS tile opened a placeholder and the recovered section was never wired
into a screen. Now: Services -> RSS (or Sources -> My feeds) lists the feeds
this device polls — name, host, cadence, per-row health — with the add form
FIRST, because adding is what the section is for.

Add is Verify-then-Add: FeedVerifier fetches like the ingestor does (same
Http.USER_AGENT, same parser) and reports what actually happened — the HTTP
code, whether the body parses as a feed, and for a 403 that the host is gating
bots so retrying is pointless. VERIFY -> VERIFYING -> ADD on success; failures
stay in the form with the reason. Local guards run before any fetch: blank
name, non-http scheme, duplicate URL (trailing slash ignored).

Rows carry a status dot, the last sync and the HTTP code. Seeded rows are
polled by the catalog pass, not as rules, so their health is matched by URL —
that is what stops them reading "awaiting first sync" while syncing hourly.
Seeded rows get no toggle and no delete: the repository refuses and the UI
does not offer it.

Schema v10 adds rules.interval_sec (nullable, no DEFAULT) so a user feed can
override the cadence the catalog feeds use; blank in the form means that
default. Beyond ADR 0002's stated scope (it left cadence to the scheduler) —
flagged for amendment rather than left as drift.

Verified on device: 9 seeded feeds listed with real health, add form leading,
no crash. 240 tests green.

## feat(ui): row spines, day groups, submit glyph, new-items pill

_2026-09-15_

The components the News and Weather screens already call, committed on their
own so HEAD builds: subjectSpineColor (the one owner of the subject-to-colour
map, replacing per-row copies; cream fallback for no subject), subjectSpineColor
drives the 3dp leading spine, Games gains Periwinkle as its subject accent while
Festival stays deliberately unmapped, DayGroup/groupIntoDays hold the day
language, Glyph.Forward replaces the drawn-arrow text in the search bar, and
NewItemsPill is the drift indicator for rows that landed above the reader.

Found while committing: those screens referenced all of this from HEAD, so the
tree did not build. Same pattern as the missing Sql.kt — verify a slice with a
clean-tree build before calling it done.

## feat(rules): poll user rss rows, one source string per rule

_2026-09-15_

Salvaged, reviewed remainder of the dead RSS-manager agent: user (never
seeded) rss rules poll through the catalog's fetch/parse/store path with the
same cache discipline, and every rule owns its source string in RuleSources
(search keeps gnews:<slug>; user rss is userrss:<ruleId>) so ingest and
retag recovery resolve identically. Http gains a single shared User-Agent
const. The manager UI itself was never built — that follows.

234 tests green, device clean.

## fix(news): complete the v9 slice left out of its commit

_2026-09-15_

The sectioned-paging commit shipped EventDao methods referencing SQL that was
never committed, so every fresh checkout failed Room compilation while this
tree stayed green on uncommitted files. Completing it: the day-headers and
per-day page queries (COUNT(1) over the timestamp index), the v9 backfill
test, the DAO fakes for the new reads, and the day-query suite.

Lesson recorded: verify with a clean-tree build before calling a slice done.

## feat(money): M&M tile with FX watch and finance news

_2026-09-15_

The Money tile routed to a blank placeholder. It is now M&M (Money and
Market), structured like Weather: an FX WATCH summary card (lead pair big,
ECB/CACHED source note, honest NOT AVAILABLE empty state), a Pairs strip
(static cells — pairs have no drill-down, so nothing pretends to tap), and a
Markets & money finance-tagged news list with day-group sticky headers,
spines, meta lines and the new-items pill. Finance rows open the article
(News behavior — a dead-end row would be worse). Screen title reads
"Money and Market" in full.

Home's sample money cell and the glance FX swipe are untouched (both observe
the same FxProvider — one fact, one owner, no new fetch, no data changes).

Verified on device: FX card, pairs, news list with sticky TODAY header, no
crash. 232 tests green.

## fix(ui): forecast strip and places fill the card width

_2026-09-15_

Fixed-width columns left a blank gutter at the card's right edge. Both rows
now share the width equally with a minimum per cell (52dp forecast days,
96dp places) and scroll only on screens narrower than all minimums.

Verified on device for the strip; Places same pattern, same treatment.
228 tests green.

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
