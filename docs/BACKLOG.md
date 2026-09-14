# Backlog

Open items, one line each. Details live in the linked docs.

## Timeline / Radar
- [ ] **Timeline model** — agree the rule: *tab = saved filter*, *rule = saved filter + delivery*. Needs a `filters` table `(name, condition, delivery, position, builtin)`; tabs render from it instead of the hardcoded `RADAR_TABS`.
- [ ] **Categories must be cross-source.** Today `NEWS`/`INCIDENT` are source-derived while `MONEY`/`PROMO` are SMS-derived, so they never mix. Promo must include product launches (credit cards, bonds, MIS), not just SMS offers.
- [ ] **Fix duplicate feed items.** Dedupe key is `(source, type, timestamp, title)` and `type` changed `news` → `feed`, so every article is stored twice. Key must be stable: `(source, url ?: title)`. Requires a schema bump + re-ingest.
- [ ] **Curated default view.** `All` is currently greedy-by-recency (news wins every slot). Should round-robin across categories with per-category caps.
- [ ] **TabStrip does not scroll horizontally** — the last tabs are unreachable.

## Incidents / maintenance
- [ ] **User-chosen status sources.** Paste a status-page URL → detect platform → poll. (Statuspage: `/api/v2/status.json`, `/api/v2/incidents/unresolved.json`.)
- [ ] **Drop `history.rss` sources** — they are incident *history*, which is why "OPEN INCIDENTS 61" is wrong. Real open count came out as 1 (Cloudflare minor).
- [ ] **AWS is not Statuspage** (`status.aws.amazon.com/data.json` is UTF-16) — own adapter or defer.
- [ ] **Maintenance from SMS/news.** Bank/ISP scheduled maintenance rarely has a status page. Detect "scheduled maintenance", "down for maintenance", "unavailable from X to Y" in SMS and news into the same INCIDENT bucket.

## WebSessionSource (Cloudflare-walled sites)
- [ ] **Downdetector probe.** Its site is behind a Cloudflare JS challenge (`Just a moment...`) — 403 on every path even with full browser headers or Googlebot UA, and the official API is Enterprise-only. Planned route: `WebSessionSource` —
  1. open the page once in a real WebView (`javaScriptEnabled`, `domStorageEnabled`, Chrome UA) so the challenge solves;
  2. persist cookies via `CookieManager` + a `CookieStore` in SharedPreferences (`cf_clearance` is bound to IP + UA);
  3. discover the data request with `WebViewClient.shouldInterceptRequest` (log every URL) or a JS hook on `fetch`/`XHR`;
  4. poll it headlessly with `HttpURLConnection` + saved cookies, re-opening the WebView when clearance expires;
  5. hidden-WebView + `document.body.innerText` as the fallback.
  Probe app should answer: does the challenge pass on-device, is the content in the HTML or an XHR, and what is the exact data URL.
- [ ] Downdetector works because it is **crowdsourced** (report volume vs baseline) — which is why it covers Indian banks/ISPs that have no status page.

## Watchers
- [ ] **Watchers page** — the unbounded list of tracked values (FX now; fares later), with targets and alert state.
- [ ] **User-added watcher items** — currently static lists in `AppContainer`. Needs a store (`watchers` table: type + params) and an add flow.

## Sources
- [ ] **Place-parameterised feed discovery** — Google News `<source url>` harvesting so any place can be subscribed, not just the seeded feeds.
- [ ] **Fares** — no free live API (Amadeus decommissioned, Kiwi invite-only, Duffel test-only). Options: manual route tracking, cached affiliate data, or the WebSessionSource route.

## Docs
- [ ] **`docs/ARCHITECTURE.md`** — single index: sources → adapters → Event → Room → screens, linking ADR 0001, the plan, the feed catalog and the classification doc.

## Fonts / typography
- [x] **Bundled Noto Serif + Noto Sans** (variable, ~3.9 MB, OFL 1.1, licence in `assets/fonts/`). Replaces `FontFamily.Serif`, which meant "whatever the OEM ships" — on this device the serif rendered as a **Japanese mincho**.
- [ ] **Not bundled, on purpose:** `mono` (platform monospace) and the Devanagari/Bengali faces (~2.8 MB more). Those scripts resolve through Android's Noto fallback chain. Revisit if fallback proves inconsistent across devices.
- [ ] **Subsetting was skipped.** `pyftsubset` would cut each face to ~100-300 KB, but needs `fonttools` installed first. Worth doing before any release build.
- [ ] **Not visually verified.** The device was offline when the fonts landed; no screenshot confirms the serif changed.

## Source of truth (see `CODE-DESIGN-GUIDELINES.md` §6)
- [ ] **Nothing user-editable is persisted.** Places (`AppContainer.weatherLocations`), feeds (`FeedCatalog`), radar tabs (`RADAR_TABS`), tile configs (`NEWS_TILE`/`WEATHER_TILE`) and tag vocabulary (`HeuristicTagger.FESTIVAL_WORDS`) are all literals in code. "Add Location" in Settings does nothing today.
- [ ] **Places first** — a `places` table + `PlacesRepository : Flow<List<Place>>` behind `WeatherProvider`, seeded with the current three. That is the acceptance test from the guidelines: adding a place must change the Settings layer only.
- [ ] **`TermSource`** — move the tagger's vocabulary out of the class into an interface with bundled / user / remote implementations, so adding a festival is data, not a code edit plus a full re-tag.
- [ ] **Tiles not built:** `M&M` (Money & Market).
- [ ] **Place hierarchy / gazetteer** — fully researched (`docs/research/place-inference-signals.md`), nothing built. `RecencyPlaceRanker` still reports a single tier, so News has no location ranking.
- [ ] **Per-place sync declined** on Weather tiles: the provider has no per-place refresh, so a tile-level SYNC would silently run the global sync. Wire it when the provider gains per-place refresh.
