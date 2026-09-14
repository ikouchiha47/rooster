# Plan 0001 — India-Wide Data Sources, Places, Stocks, Email, and Alerts

- **Status:** Draft (for review)
- **Date:** 2026-09-13
- **Depends on:** ADR 0001 (source layer + zero-cost policy)
- **Companion:** `design/` (Variant A UI, chosen), `design-rules/` (rule-builder exploration)

## Goal

A **zero-dependency, zero-cost, on-device** aggregator covering **all of India** — any state, district or town — where the user configures places and gets local news, SMS/email events, stocks and alerts. No paid API is required for v1.

---

## 1. Place model — India-wide

Places are **nodes in an administrative tree**, and the tree must span the whole country, not a curated few.

```
India → West Bengal → Murshidabad → Berhampore
India → Karnataka → Bengaluru Urban → Bengaluru
India → Assam → Kamrup Metropolitan → Guwahati
```

### Levels to bundle
| Level | Count (India) | Bundle? |
|---|---|---|
| Country | 1 | ✅ |
| State / UT | 36 | ✅ |
| District | 784 (LGD) / 763 (GeoNames ADM2) | ✅ |
| Sub-district / tehsil / block | ~7,090 (LGD) | ✅ |
| City / ULB / census town | ~5,038 ULBs + census towns | ✅ |
| Village | 676,868 | ❌ **skip by default** — little matching value, large size |

Bundled as an on-device **SQLite** table keyed by LGD/GeoNames code (expected tens of MB, not GB).

Each place: canonical name, **aliases**, **parent chain**, **population**, **centroid + bbox**.
- **Tier (metro / city / town / village) is derived from population and used only for priority — never matching.**
- **Matching:** name/alias hit, or an **ancestor** (district/state) is named → inherited at lower weight (city > district > state).
- Works at **any level**: a user can configure a village-adjacent town, a district, or a whole state; a state-wide story matches every place under it.

### Gazetteer sources (bundle, offline)
| Source | Use | Licence |
|---|---|---|
| **GeoNames** `IN.zip`, `admin1/2Codes`, `alternateNamesV2`, `shapes_simplified_low` | ADM1/2/3 hierarchy, population, aliases, bbox | CC-BY 4.0 |
| **LGD** (Local Government Directory) | Authoritative state/district/sub-district/ULB codes | Free (snapshot the form once) |
| **Census 2011** town/village directory | Town population & classification | Free |
| **Natural Earth / simplified OSM** (optional) | State/district boundary polygons if geometry is needed | Public domain / ODbL |

**Do NOT geocode at runtime.** Nominatim blocked a test request and discourages periodic app use; the free Open-Meteo geocoder returned *wrong* places ("Bangalore" → Pakistan, "Berhampore" → New Zealand). Resolve locally.

**Geometry:** not needed for v1. S2/H3/geohash solve spatial joins at scale; with name + hierarchy matching, a **bbox or haversine** over bundled places is enough. Add **SQLite R\*Tree** only if geotagged items appear. A map UI (MapLibre + offline PMTiles) is out of scope for v1 and absent from the chosen design.

### Disambiguation
Mandatory — India is full of near-duplicates. Confirmed: **Berhampore** (WB/Murshidabad) vs **Berhampur** (Odisha/Ganjam) — ~470 km apart, different in Bengali (`বহরমপুর` vs `ব্রহ্মপুর`). Resolve by **state + district + coordinates**, never bare English name.

### Language coverage (crucial for "all of India")
Google News regional editions that **exist**: `en, hi, bn, ta, te, mr, gu, ml, pa, ur`.
**Missing: Kannada, Odia, Assamese.** Verified empirically (Sep 2026): `hl=kn-IN` **302-redirects to `hl=hi`** — the feed comes back as `<language>hi</language>` with Hindi results — whereas `hl=bn-IN` returns `<language>bn</language>` with genuine Bengali results. `hl` is the *edition* language (what language the results are in), not a filter on query language: a Kannada-script query on the `en-IN` edition returns **English** articles about that place.

So for **Karnataka, Odisha and Assam**, local-language coverage must come from **direct outlet feeds**, not Google News. This makes the state-level catalog (below) load-bearing in those states.

---

## 2. Source layers — feeds first, discovery free

**Rule: subscribed feeds are the steady state. Search exists ONLY to find parseable sources — never as a content source, never on a polling schedule.** A discovered source becomes a directly-polled adapter, fetched for free.

### Tier A — free, no keys (the whole v1 core)
| Mechanism | What it does |
|---|---|
| **Subscribed feeds** | Manual add + OPML import; the steady state |
| **Feed autodiscovery** | Paste a site URL → find its RSS/Atom |
| **Curated starter catalog** | National + per-state outlets, pre-verified (see §6) |
| **Place-parameterised Google News harvesting** | For **any configured place**, generate queries in English **and the place's local language**, query Google News RSS, harvest `<source url>` domains, run autodiscovery → suggested sources |
| **Manual add** | Paste an RSS URL directly |

The last one is the scaling trick: **discovery is generated per place and per language**, so it covers every district without a hand-built feed per district.

### Tier B — free, keyless
- **DuckDuckGo** `html.duckduckgo.com/html/?q=` / `lite.duckduckgo.com/lite/` — general-web suggestions (blogs/forums Google News misses). Both confirmed working Sep 2026; first page needs no `vqd`; links wrapped in `?uddg=` → decode. Stay **< ~30 req/min**; 202/403 when flagged (carrier IPs too); ToS-gray. **Not** the Instant Answer API.

### Tier C — budgeted, OPTIONAL and DEFERRED
- **Brave Search API** ($5/mo free credits, sanctioned), **Exa** ($10/mo credits, semantic + bundled text), **Tavily** (1,000 credits/mo, `/map` finds `/feed`).
- Only worth adding if Tier A+B prove thin. **V1 does not depend on any of these** — no keys, no credit budgets, no Bengali search risk.

### Feed autodiscovery (confirmed)
1. `<link rel="alternate" type="application/rss+xml">` (also atom/feed+json); 2. HTTP `Link:` header; 3. exact MIME types; 4. fallbacks `/feed`, `/feed/`, `/rss.xml`, `/atom.xml`, `/feed.xml`, `/?feed=rss2`, YouTube `feeds/videos.xml?channel_id=`, `medium.com/feed/@user`.
**Reality:** only ~19.7% of pages / ~35.9% of sites advertise a feed → many candidates end at "web-page-to-feed" (static HTML, no JS) or get skipped.

---

## 3. Stocks / share prices

**Blunt: free real-time Indian equity is not achievable.** Live-verified Sep 2026.

| Source | Status | Endpoint | Freshness |
|---|---|---|---|
| **NSE daily Bhavcopy** (UDiFF) | FREE, keyless | `nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_YYYYMMDD_F_0000.csv.zip` | EOD |
| **NSE legacy bhavcopy** | FREE, keyless | `nsearchives.nseindia.com/products/content/sec_bhavdata_full_DDMMYYYY.csv` | EOD |
| **NSE announcements** | FREE keyless RSS | `nsearchives.nseindia.com/content/RSS/Online_announcements.xml` | near-real-time |
| NSE `quote-equity` JSON | FREE but **403** (anti-bot) | `nseindia.com/api/quote-equity?symbol=…` | — |
| **BSE Bhavcopy** (UDiFF) | FREE, keyless | `bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_YYYYMMDD_F_0000.csv` | EOD (after 16:45 IST) |
| BSE unofficial quote | FREE | `api.bseindia.com/BseIndiaAPI/api/getScripHeaderData/w?scripcode=…` | near-live |
| **Yahoo Finance v8 chart** | FREE, keyless, no crumb | `query1.finance.yahoo.com/v8/finance/chart/{SYM}.NS\|.BO?interval=1d&range=1d` | **~15 min delayed** |
| Stooq | **effectively DEAD keyless** | CAPTCHA key required since ~Apr 2026 | — |
| Alpha Vantage / Finnhub / Twelve Data / FMP / Tiingo / Marketstack / Polygon | free tiers | **none give usable free Indian equities** | US-only or tiny |
| **Binance** `data-api.binance.vision`, **CoinGecko** (demo key 100/min, 10k/mo) | FREE | crypto | near-real-time |

**Recommendation:** **Yahoo v8** for intraday/threshold polling (~15-min delayed; poll every 15–30 min) + **NSE/BSE Bhavcopy** for EOD truth. Label watcher alerts **delayed**, never "real-time". Crypto via Binance/CoinGecko.

**Indian market news RSS (verified 200):** ET Markets `economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms`; Mint `livemint.com/rss/markets`; Business Standard `business-standard.com/rss/markets-106.rss`; NSE announcements. **Moneycontrol RSS = 403** (Akamai).

---

## 4. Email

**Recommendation: skip IMAP. Use an email-to-feed inbox.** Newsletters are the useful 90%, with **zero credentials on device**.

| Option | Free? | Notes |
|---|---|---|
| **Kill the Newsletter** | ✅ unlimited, open-source | Unique inbox + Atom feed. **Substack blocks its addresses** → auto-forward workaround or self-host |
| **Cloudflare Email Routing + Email Worker** | ✅ (own domain) | Receive at `anything@domain` → Worker parses MIME → webhook. Strongest DIY |
| Feedbin | $5/mo | email→RSS, paid |

**If a real mailbox is needed** (arbitrary senders/attachments): **Gmail IMAP + app password** (works with 2FA).
- Library: **Eclipse Angus Mail 2.x** (Jakarta Mail); `com.sun.mail:android-mail:1.6.8` is the mature port. No SASL on Android → LOGIN/PLAIN.
- Incremental fetch only: track **UIDVALIDITY + last UID**, fetch `UID <n+1>:*`. Never rescan.
- Gmail quirks: label/All-Mail duplication; `\Seen` propagation over IDLE unreliable.
- **IDLE does not survive Doze** → WorkManager ≥15 min polling; foreground IDLE only while viewing.
- Providers: Gmail ✅, Yahoo ✅; Fastmail requires paid; Proton needs paid Bridge; **Outlook/M365 = OAuth only (no free path)**.

---

## 5. Source types worth supporting first (learned from Inoreader)

| Source type | Replicable free? | How |
|---|---|---|
| **RSS/Atom** | ✅ | Backbone |
| **Email newsletter → feed** | ✅ | Kill the Newsletter / own Worker |
| **Reddit** | ✅ | `reddit.com/r/<sub>/.rss` |
| **YouTube** | ✅ | `youtube.com/feeds/videos.xml?channel_id=` |
| **Mastodon** | ✅ | `<instance>/@user.rss` |
| **Bluesky** | ⚠️ | Public AT Protocol XRPC; profile RSS is text-only |
| **Google News query RSS** | ✅ | Free topic/place coverage |
| **Static web-page → feed** | ⚠️ | jsoup on static HTML; **no JS** → SPAs fail |
| **Podcasts** | ✅ | `<enclosure>` + optional on-device whisper.cpp |
| Telegram channels | ⚠️ | Needs bot/MTProto |
| Twitter/X | ❌ DEAD (Apr 2023) | — |
| Facebook pages / LinkedIn | ❌ | Auth-gated |

---

## 6. Starter catalog — national + state-level

A bundled, pre-verified catalog so the app is useful on day one **anywhere in India**:
- **National** (English): The Hindu, Indian Express, Times of India, Hindustan Times, NDTV, Mint, Business Standard, ET… plus PIB-equivalents that actually work (PIB's RSS is broken — use others).
- **Per state/UT (36):** the 1–3 leading outlets, **including a local-language one** where they publish.
- **Regional-language feeds are mandatory for Karnataka, Odisha, Assam** (no Google News edition → the catalog is the only local-language path there).
- **Districts are NOT catalogued** (784 is unmaintainable) — they come from **place-parameterised discovery** (§2).

> A companion research pass (`docs/research/india-feed-catalog.md`) is generating the verified national + state feed URLs.

---

## 7. Detection pipeline (worked example: bandh)

1. **Deterministic lexicon** (EN + BN + other local scripts) — bandh, hartal, dharna, gherao, blockade, chakka jam, shutdown, curfew, Section 144; BN: `বনধ`, `হরতাল`, `ধর্মঘট`, `অবরোধ`, `নাকাবন্দি`, `মিছিল`, `কারফিউ`, `বিক্ষোভ`.
   - **Trap:** `বন্ধ` = "closed" (shops/hotels), ~28 false positives in one sample — **never substring-match `বন্ধ`; only `বনধ`.**
   - Normalise **NFC**, strip ZWNJ/ZWJ. Legacy **Bijoy/SutonnyMJ** sources break matching entirely.
2. **LLM confirm + extract** — Gemma 4 E2B, **JSON-schema constrained decoding**.
3. **Dedupe** — across outlets via `source+URL` + embedding similarity.
4. **Place match** — against the place tree, specificity-weighted, works at any admin level.

---

## 8. Alerts

Rule: *"bandh/shutdown event matching place ∈ {…} or their ancestors"*, priority from specificity + source reliability + recency, push + digest, quiet hours.

**Honesty principle (UI):** never render "no news found" as "no bandh." Metro/big-city coverage is good; **district and small-town coverage is best-effort** — expect hours, not minutes, and a town-only shutdown may be missed entirely.

---

## 9. Module → source matrix

| UI element | Needs | Status |
|---|---|---|
| Timeline, Money, OTP, Travel(SMS) | SMS read + parse | ✅ proven |
| News + `region IN` chips (any place) | feeds + place matching | ✅ best-effort, India-wide |
| Weather widget | weather API | ✅ Open-Meteo |
| Radio now-playing | station DB + streams | ✅ radio-browser |
| **Stocks** | quotes + EOD | ⚠️ EOD + ~15-min delayed; no free realtime India |
| **Email** | newsletters → events | ✅ via email-to-feed (Gmail IMAP optional) |
| Social | open protocols | ⚠️ Bluesky/Mastodon now |
| Alerts + dry-run preview | rules engine + event history | ✅ local |
| `sources OK / feeds OK / last sync` | adapter health + scheduler | ✅ local |
| Place filter (India-wide) | gazetteer | ✅ bundled |
| Suggested feeds + trust/relevance | Tier A discovery (+ Tier B) | ✅ free; Tier C deferred |
| Topics "from your reading" | local entity frequency | ✅ local |
| Scan (QR), Notes, Pinned, Digest, Snooze | local | ✅ local |
| **Jobs** | job listings | ❌ no free source |
| **Travel / flight prices** | flight prices | ❌ no free live API |

---

## 10. AI placement

- **Deterministic parsers** — SMS classification (bank/OTP/travel/promo), lexicon matching.
- **EmbeddingGemma 308M** — relevance + dedupe.
- **Gemma 4 E2B** (2.59 GB, Apache-2.0, already on device) — hard minority: NL→rule, messy extraction, summaries. **CPU, JSON-schema constrained decoding.**
- Pending: eval **Gemma 3 1B int4 (584 MB)** vs **4 E2B** on valid-JSON %, field accuracy, tok/s, RAM.

## 11. Key handling

No keys needed for v1. If Tier C is ever enabled: user enters keys **on-device**, stored in Keystore-encrypted prefs, rotated ~90 days.

---

## 12. Build order

1. **Model eval harness** (1B vs E2B).
2. **India-wide gazetteer** — bundle GeoNames IN (ADM1–3) + LGD snapshot (skip villages); place-tree matcher + disambiguation.
3. **`SmsSource`** — query + `ContentObserver` → canonical `Event`; deterministic classifier.
4. **Feed adapter + autodiscovery + OPML import**; adapter health/backoff.
5. **Starter catalog** (national + per-state) + **place-parameterised Google News harvesting**.
6. **Email-to-feed adapter.**
7. **Stocks adapter** — Yahoo v8 + NSE/BSE Bhavcopy.
8. **Rules engine + dry-run** (Form builder).
9. **Notifications** — push + digest + quiet hours.
10. **Scope decision** — descope/relabel Travel + Jobs tiles.
11. *(Deferred)* Tier B DDG, then Tier C if justified.

## 13. Non-goals
X, LinkedIn, flight/e-commerce prices, **real-time free Indian equity quotes**, government feeds, runtime geocoding, public SearXNG, map rendering (v1), any paid API as a **foundation**.

## 14. Open risks
1. **Language gaps:** no Google News edition for Kannada/Odia/Assamese → those states depend on the outlet catalog.
2. **District/small-town coverage is uneven** — no free hyperlocal wire exists; best-effort by design.
3. **NSE/Yahoo endpoints are unofficial** — 403/429/format changes. Bhavcopy is the stable fallback.
4. Google News RSS is ToS-gray; DDG can start returning 202/403 from a carrier IP.
5. Feed rot (~2/3 of sites have no feed) → periodic re-discovery.
6. Email via app password can break silently when a provider migrates to OAuth.
7. If Tier C is added later, Bengali/regional indexing quality is unverified — validate first.

---

## 15. Verified source additions (post-catalog)

- **Catalog built:** `docs/research/india-feed-catalog.md` — 170 entries, **169 verified** (HTTP 200 + well-formed XML); **all 36 states/UTs** have ≥1 working feed.
- **Guaranteed baseline:** The Hindu publishes a per-state feed for every state/UT (`/news/national/<state>/feeder/default.rss`, Delhi/Puducherry under `/news/cities/`), so English state coverage exists even where no local outlet has a feed.
- **The Google-News language gaps are covered by feeds:** News18's `commonfeeds` (`/<locale>.news18.com/commonfeeds/v1/<code>/rss/<slug>.xml`) provides state **and district** feeds in 11 languages including **Kannada, Odia, Assamese**; plus Prajavani / Kannada Prabha (KN), Dharitri / OdishaTV / News18 Odia (OD), Asomiya Pratidin (AS).
- **New adapter type — "sitemap-index source":** poll an XML sitemap as an index where there is no RSS.
  - **ETV Bharat** (no RSS): `etvbharat.com/<lang>/<state>/latest_news_sitemap.xml` + per-date archives; article pages are **server-rendered** → jsoup-parseable; covers Kannada/Odia/Assamese/Bengali/Tamil/Telugu/Malayalam/Marathi/Gujarati/Punjabi/Urdu/Hindi.
  - **The Hindu / Hindustan Times**: sitemaps *augment* their RSS as a completeness sweep; HT's `/sitemap/index.xml` is a full-archive sitemapindex (764).
- **Blocked — do NOT bundle as RSS:** Moneycontrol, NDTV direct, Telegraph, Zee News, Times Now, Anandabazar, The Tribune, Lokmat, Sakshi, Dinakaran, Kerala Kaumudi, Daily Excelsior, Divya Himachal, Babushahi (all 403); ThePrint (Cloudflare challenge); The Wire / Scroll.in (HTML-only).
- **Weakest coverage:** Lakshadweep, Dadra & Nagar Haveli and Daman & Diu, Puducherry, Andaman & Nicobar, Chandigarh, Punjab, and the NE states (English-only in practice; no usable Khasi/Garo/Mizo/Meitei feeds found).
- **Build-order impact:** item 4 (feed adapter) gains the **sitemap-index source**; item 5's catalog is now concrete and can be bundled.
