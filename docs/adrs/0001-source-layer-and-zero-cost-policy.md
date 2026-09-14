# ADR 0001: Source Layer, Zero-Cost Policy, and On-Device AI Placement

- **Status:** Proposed (awaiting owner ratification)
- **Date:** 2026-09-13
- **Deciders:** Owner (single user)
- **Scope:** Personal Radar — source ingestion, normalization, scheduling, and on-device AI. Not UI, not alerts UX.

---

## Context

Personal Radar is a **personal-use, single-user, sideloaded Android app**. It is never distributed via an app store. It runs on a Nothing Phone (2a)-class device (Dimensity 7200 Pro, 8–12 GB RAM, arm64, Android 16).

Three hard constraints drive every decision below:

1. **Zero recurring cost.** No server, no paid APIs, no metered backends.
2. **No JS rendering.** Plain HTTP fetch + parse only. No headless browser, no WebView scraping.
3. **On-device only.** Fetching and processing happen on the phone. No always-on backend.

The app aggregates many heterogeneous sources into one timeline and one rule engine. The core architectural problem is therefore: *how do we ingest a wide set of free sources uniformly, without a server, without a browser, and without any per-item cost?*

Verified on-device (Sep 2026): a sideloaded, non-default app granted `READ_SMS` reads `content://sms` successfully (2,474 rows, no `SecurityException`). SMS needs **no default-SMS-app role** — only a one-time `adb pm grant`. This makes SMS the highest-value structured source and confirms local-data sources need no network and no cost.

---

## Decision

1. **Sources are tiered by cost and access method; only Tier 0 and Tier 1 are in scope for v1.**
2. **A single canonical `Event` model** normalizes every source into one stream.
3. **A `Source` adapter interface** (`fetch → normalize → emit`) isolates per-source quirks, scheduling, and health.
4. **A rule/alerts engine matches over normalized Events**, so one rule schema spans SMS, news, fares, and social.
5. **The zero-cost policy is an explicit, enforceable gate** applied at adapter admission, not an aspiration.
6. **On-device AI is placed by discipline:** deterministic parsers for the high-volume majority, embeddings for relevance/dedupe, a small LLM only for the hard minority. No per-item LLM classification.
7. **Flight and e-commerce price monitoring are out of scope for v1** (no free path exists).

---

## Source Tiering

### Tier 0 — Local, no network, no key (core)

| Source | Access | Notes |
|---|---|---|
| SMS | `content://sms` via `READ_SMS` | Proven on device. One-time `adb pm grant`. Best structured source: bank/OTP/travel. |
| Calendar / device feeds | Android content providers | Local; no cost. |

Tier 0 is always available, deterministic, and requires no scheduling budget.

### Tier 1 — Free, no key, network (core)

| Source | Access | Constraints |
|---|---|---|
| RSS/Atom | `com.prof18.rssparser` | Primary general feed mechanism. |
| Static HTML | `jsoup` | Static pages only. Cloudflare/JS challenges are unsolvable by plain HTTP → **out of scope**. |
| Feed autodiscovery | Parse `<link rel="alternate" type="application/rss+xml">` (+ atom, feed+json); fall back to `/feed`, `/rss`, `/rss.xml`, `/atom.xml`, `/feed.xml` | Turns a user-entered URL into a feed where possible. |
| Weather | Open-Meteo | 10k/day, **attribution required**. |
| Radio | radio-browser.info | Send a descriptive `User-Agent`; resolve mirrors via DNS. |
| FX | Frankfurter / ECB | Keyless. |
| Crypto | Binance public data | Keyless (`data-api.binance.vision` if geo-blocked). |
| GitHub | Atom feeds + unauthenticated REST | 60 req/hr/IP. Feed where possible; REST sparingly. |
| Bluesky / AT Protocol | `public.api.bsky.app` | Keyless; ~3,000 req / 5 min / IP. Following feed is algorithmically ranked in practice → **sort by `createdAt` client-side** for true chronology. |
| Mastodon | Public timelines | 300 req / 5 min / IP. |
| YouTube | `feeds/videos.xml?channel_id=UC…` | **Flaky since Dec 2025** (intermittent 404s) → retry/backoff. `?playlist_id=UULF…` drops Shorts. |

### Tier 2 — Free-with-key / OAuth (optional, gated, off by default)

| Source | Access | Constraints |
|---|---|---|
| Email | Gmail **IMAP + 16-char app password** | Requires 2FA; revoked on password change; unavailable under Advanced Protection. No OAuth project. **Preferred email path.** |
| Reddit | OAuth API | OAuth mandatory (unauth → 403); 100 QPM / client id; since ~Jun 2026 an **approval gate**. Optional. |
| Crypto (alt) | CoinGecko demo key | Keyed fallback. |

Tier 2 adapters are compiled in but **disabled by default** and require explicit user opt-in plus a key/OAuth grant. They never activate silently.

### Tier 3 — Paid / dead (explicit non-goals, see below)

---

## Adapter Interface

Every source, regardless of tier, implements one interface. Shape (conceptual, not code):

```
SourceAdapter:
  id: String
  tier: Tier                 // 0 | 1 | 2
  requiredPermissions: []    // e.g. READ_SMS
  requiresCredentials: Bool  // false for Tier 0/1
  fetch(cursor, budget) -> RawBatch
  normalize(RawBatch) -> [Event]
```

Rules:

- **fetch** is I/O only; it never interprets domain meaning.
- **normalize** is pure and deterministic where possible (parsers, not LLMs).
- **emit** produces canonical `Event`s; the scheduler and rule engine only ever see Events.
- Each adapter owns its own **scheduling policy, backoff, and health state** (last success, last error, consecutive failures). One broken source must never stall the stream.
- **Provenance** (`source`, `adapter_id`, fetch timestamp, original URL) is preserved on every Event for traceability and dedupe.

---

## Scheduling

- Periodic **WorkManager**-style jobs on-device; no always-on process, no push backend.
- Per-source cadence driven by expected update rate and rate limits (e.g. GitHub 60/hr, Mastodon/Bluesky per-IP windows).
- **Exponential backoff with jitter** on failure; adapters marked unhealthy after N consecutive failures and probed at a low frequency thereafter.
- **Rate-limit budget** is enforced per adapter so a single chatty source cannot starve others.
- Flaky sources (notably YouTube RSS) get retry/backoff rather than removal.
- Work is batched and constrained (network availability, battery/charging), consistent with the zero-cost, on-device model.

---

## On-Device AI Placement

**Constraint:** Google **AI Edge Gallery** is a standalone showcase app — no IPC/AIDL for other apps, no inbound API, model files are app-private. **It cannot be called.** The app must ship its own model.

**Stack:** **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`). MediaPipe LLM Inference is maintenance-only and not chosen. API shape: `EngineConfig(modelPath, Backend.GPU())` → `Engine.initialize()` → `createConversation()` → `sendMessageAsync(): Flow`. Model files are `.litertlm`.

**Models (Apache-2.0):**

| Model | Size | Use |
|---|---|---|
| Gemma 4 E2B | 2.58 GB (mobile QAT ~0.84 GB) | Hard-minority LLM tasks. |
| Gemma 3 1B | ~0.5 GB | Smaller fallback. |
| EmbeddingGemma 308M | ~180–300 MB | Relevance/dedupe; 768→128 Matryoshka dims, 2K ctx. Via LiteRT or AI Edge RAG SDK. |

**Acceleration:** Dimensity 7200 Pro is **not** on LiteRT-LM's NPU vendor list → use **GPU (OpenCL, requires `libOpenCL.so` in the manifest)** or CPU. Gemma 3 1B and Gemma 4 E2B fit; **E4B is borderline on 8 GB** and is not the default.

**Placement discipline (the decision):**

1. **Deterministic parsers** handle the high-volume majority — bank/travel/OTP SMS, feeds, prices. No LLM in this path.
2. **Embeddings** handle relevance and dedupe across the normalized stream.
3. **A small LLM** handles only the hard minority: NL→rule parsing and summarization. **Not** per-item classification.

This keeps cost at zero, latency predictable, and keeps the model off the critical ingestion path. The model is downloaded once by the user and stored app-private.

---

## Storage / Event Model

One canonical Event, so every source (SMS, news, fares, social) is queryable and rule-matchable uniformly:

```
Event:
  source: String            // "sms" | "rss:<id>" | "weather" | ...
  type: String              // category / semantic kind
  timestamp: Instant
  title: String
  content: String
  entities: [String]        // people, orgs, tickers, stations
  location: Geo?            // optional coordinates/place
  fields: Map<String, Num>  // amount, fare, price, etc.
  url: String?              // provenance / deep link
```

- Normalization happens in adapters; downstream code sees only Events.
- Numeric `fields` is what makes a fare/price/amount comparable across sources.
- `source` + adapter id + URL/time form the dedupe key alongside embedding similarity.

---

## Rule / Alerts Engine

- Rules match over **normalized Events**, not raw source payloads.
- One rule schema spans all sources — e.g. "notify if an event from any source matches entity X, amount > N, within window W."
- Enables cross-source rules (bank SMS amount vs. price field; travel SMS vs. ...) without per-source rule code.
- Rule authoring may use the small LLM (NL→rule) but matching itself is deterministic.

---

## Non-Goals (Explicitly Rejected Sources)

The **zero-cost policy** is enforceable, not aspirational:

> **Rejected if:** it requires a paid key/subscription, a paid marketplace, or a browser/JS engine to render.

Explicit rejections for v1:

| Source | Reason |
|---|---|
| X / Twitter | Free tier removed Feb 2026; pay-per-use (~$0.005/post read). Chronological following feed not viable free. |
| LinkedIn | Feed API deprecated ("access no longer granted"); scraping banned and litigated (Proxycurl shut down). |
| Flights | **No free live API left.** Amadeus Self-Service decommissioned 17 Jul 2026; Kiwi/Tequila invite-only; Duffel test-mode-only (fake prices); Travelpayouts free only with an affiliate account and returns 7-day cached fares. No public Google Flights API. |
| E-commerce price tracking | Keepa paid-only (no trial); CamelCamelCamel has no API. |
| RapidAPI | Paid marketplace with tiny free tiers. |
| Free stock quotes | No authoritative free no-key source found. |
| Radio Garden | No public API. |
| Cloudflare/JS-challenge sites | Cannot be solved by a plain HTTP client. |

**Consequential trade-off:** because no free path exists, **flight and e-commerce price monitoring are out of scope for v1.** Cached affiliate fare data and scraping are explicitly rejected — they violate zero-cost, no-browser, or both. The Event model keeps a `fields` slot so these can be added later *if a genuinely free path appears*.

**Adding a future paid source:** it must be an **obviously-separate optional adapter** — Tier 3 — disabled by default, requiring explicit opt-in and a user-supplied key, with its cost clearly surfaced. It must never contaminate the zero-cost baseline. The admission test ("paid key or browser → rejected") stays the default.

---

## Consequences

**Positive**

- Zero recurring cost holds structurally: every v1 source is free, keyless, or optional-with-user-key.
- SMS is the strongest source and needs no default-app role — only a one-time `adb pm grant`.
- Uniform Event model enables one rule engine and cross-source alerts.
- On-device-only avoids server cost, privacy exposure, and an always-on dependency.
- AI is off the high-volume path, so ingestion stays cheap and deterministic.

**Negative / accepted**

- No flight or e-commerce price monitoring in v1.
- RSS/static-HTML-only excludes JS-heavy sites and Cloudflare-protected sources.
- Rate limits (GitHub 60/hr, Bluesky/Mastodon per-IP windows) constrain polling cadence.
- YouTube RSS is flaky and needs retry/backoff.
- Bluesky's following feed is algorithmically ranked; true chronology requires client-side `createdAt` sort.
- GPU path requires `libOpenCL.so` in the manifest; no NPU acceleration on this SoC.
- App must ship/download its own LLM; AI Edge Gallery cannot be reused.

**Risks**

- Tier 1 free endpoints are not contractual and can change without notice.
- Model download size (up to ~2.58 GB for full Gemma 4 E2B) is a real storage/one-time-download cost; QAT (~0.84 GB) and Gemma 3 1B mitigate.
- The Reddit approval gate may block the optional Tier 2 adapter.

---

## Open Questions

1. **LLM default:** Gemma 3 1B for predictable memory, or Gemma 4 E2B QAT for quality within an 8 GB device? Needs on-device measurement.
2. **Embedding usage:** whether EmbeddingGemma runs on every Event or only on a dedupe/relevance candidate subset (affects battery and latency).
3. **Database choice:** Room/SQLite vs. an embedded store, given full-text + vector similarity needs.
4. **Rate-budget policy:** exact per-adapter cadence and what to drop when limits are hit.
5. **Tier 2 activation UX:** how to keep opt-in unmistakable while not over-building credential handling for one user.
6. **Fallback if YouTube RSS degrades further** — is `?playlist_id=UULF…` a durable substitute?
7. **Re-evaluation trigger:** what change (a genuinely free flight/price API, or a free X tier) is enough to revisit the non-goals?
8. **Cloudflare:** is there any free, no-browser path (e.g. official APIs hiding behind a challenges page) or is it permanently out of scope?
