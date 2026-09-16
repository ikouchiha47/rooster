# RBI as a source, and Indian bank holidays

- **Date:** 2026-09-16
- **Status:** researched and verified; nothing implemented
- **Related:** ADR 0003 (source layer), plan 0002. Nothing here is built yet.

Everything marked **verified** was fetched live during this research (see the
verification table at the end). Unverified claims are marked as such.

---

## 1. Corrections to the assumptions that prompted this

Two premises turned out to be wrong, and both matter for the design:

1. **Andhra Pradesh is not missing from the RBI holiday list.** RBI names *cities*
   (its own regional offices), and AP appears as **Vijayawada** — verified present in
   the current matrix, alongside Hyderabad and Chandigarh. The confusion is real but
   its cause is naming: aggregators carry "Hyderabad" (Telangana) and drop
   "Vijayawada", so AP vanishes from their output. Fixing this is a curated
   city→state map, not a new data source.
2. **The RBI list omits different things: union territories, not states.** There is
   no office for **Puducherry, Ladakh, Lakshadweep, Andaman & Nicobar, Dadra & Nagar
   Haveli and Daman & Diu** — verified absent. Punjab and Haryana have no office
   either (Chandigarh covers both), so per-state disaggregation there is ambiguous.

Also worth stating plainly: **there is no single consistent source.** Not an
official one, not a free API. Consistency is assembled, not fetched.

---

## 2. RBI as a news source — official RSS, verified live

RBI publishes an RSS index page (`https://www.rbi.org.in/Scripts/rss.aspx`) listing
its feeds. These are official and alive:

| Feed | URL | Verified |
|---|---|---|
| Press releases | `https://rbi.org.in/pressreleases_rss.xml` | 200, `text/xml` |
| Notifications | `https://rbi.org.in/notifications_rss.xml` | 200, `text/xml` |
| Speeches | `https://rbi.org.in/speeches_rss.xml` | 200, `text/xml` |
| Tenders | `https://rbi.org.in/tenders_rss.xml` | 200, `text/xml` |
| Publications | `https://rbi.org.in/Publication_rss.xml` | 200, `text/xml` |

**The load-bearing fact: each feed carries only the latest 10 items.** Verified by
counting `<item>` elements (10, 10, 10). They are a window, not an archive — poll at
least daily or items are lost silently. Dedupe by `<link>`, not by title.

Notes that will bite otherwise:

- Content-Type is `text/xml`, not `application/rss+xml`. Parse as generic XML.
- Item `<link>` is the RBI detail page (`BS_PressReleaseDisplay.aspx?prid=…`); the PDF
  link is inside `<description>` on `rbidocs.rbi.org.in`.
- Plain HTTP client with any UA works; no auth. Use `https` (http redirects).
- An item's identity should be the `prid`/`Id` query value or the detail URL — stable
  and mechanism-independent, per ADR 0003 §3.

**Listing pages** are server-rendered and jsoup-scrapeable, so archives are reachable
in principle: `BS_PressReleaseDisplay.aspx` (press releases) and
`NotificationUser.aspx` (notifications), each row carrying date, title, detail link
and a PDF. But **RBI's WAF returns HTTP 418 to non-browser POSTs and to unrecognised
query strings** (verified), so ASP.NET postback filtering and deep pagination are not
available to a plain client. There is **no content JSON/XHR endpoint** — the only JSON
on those pages is site chrome (`GetLastUpdateDate_new`, `GetKeywords`, `SaveFeedback`).

---

## 3. Bank holidays — why consistency has to be assembled

### The legal basis (this is the root cause of the mess)

- **Section 25, Negotiable Instruments Act 1881** defines a public holiday, and the
  MHA notification **No. 20-25/26/Pub-1 dated 8 June 1957** delegated the power to
  **State Governments**. Each state declares its own NI Act holidays.
- So the authoritative per-state source is a **state gazette notification**, e.g. AP
  `G.O.Rt.No.2277`, General Administration (Political.B), dated 04.12.2025 for 2026.
- **RBI is a compiler, not the declarer.** Bank holidays are regulated by DFS,
  Ministry of Finance — not by DoPT's central holiday OM (DoPT says so explicitly).

There is no machine-readable form of these notifications. Gazette PDFs and mirrors are
what exists.

### Layer 0 — compute locally (no network, covers most days)

- **Sundays** and the **2nd/4th Saturday** rule. The RBI matrix itself states all
  scheduled and non-scheduled banks observe a public holiday on 2nd and 4th Saturdays.
- A tiny hardcoded set of all-India bank holidays: Republic Day, Independence Day,
  Gandhi Jayanti, Christmas, and **1 April (Annual Closing of Accounts)**.
- This is a `LocalDate` calculation plus ~5 rows. It is the majority of closed days and
  it works offline forever, which matters more than any of the network layers.

### Layer 1 — per-state bank calendar (refresh monthly at most)

- **Primary: HDFC's own page, `https://www.hdfc.bank.in/bank-holiday-list`** — verified
  200, ~1.2 MB server-rendered HTML, one accordion section per state/UT. This is the
  **only verified single page covering all 37 states and UTs**, including AP and the
  five UTs RBI omits (all confirmed present by grep). Parse with jsoup.
- **Official cross-check: RBI's own matrix,
  `https://www.rbi.org.in/Scripts/HolidayMatrixDisplay.aspx`** — verified 200, plain GET
  returns the **current month for all 34 regional offices**, with each cell marked for
  *Holiday under Negotiable Instruments Act* or *Banks' Closing of Accounts*. History
  reaches 2001, but the year/month filters are postback-driven and **the WAF 418s them**
  (even `m.rbi.org.in` ignored query params and returned the current month), so only the
  current month is machine-retrievable. Map the 34 office cities → states with a small
  curated table; accept the UT holes.
- **Volatility cross-check: NSE's holiday JSON,
  `https://www.nseindia.com/api/holiday-master?type=trading`** (and `type=clearing`) —
  verified 200 `application/json`, ~34 KB, keyed by segment (`CBM`, `CM`, `FO`, …) with
  `tradingDate`, `description`. Needs a browser-like UA and may need a cookie warm-up.
  BSE's equivalent is best read from the **beta** subdomain
  (`beta.bseindia.com/static/markets/marketinfo/listholi.aspx`, server-rendered);
  `bseindia.com` proper serves an Angular shell with no data.
  Use these for "is the financial system closed", **not** as a state proxy — exchange
  holidays are a smaller, mostly national subset, and they change at short notice (2026
  includes a late-added 15 Jan for the Maharashtra municipal election).

### Layer 2 — curated override table (hand-maintained, small)

The honest cost of consistency. Needed for:

- the five UTs RBI omits — **Puducherry, Ladakh, Lakshadweep, Andaman & Nicobar, DNH & DD**;
- Punjab/Haryana, where RBI's Chandigarh office blurs the boundary;
- **late changes** — moon-sighting (Eid, Bakrid, Muharram, Eid-e-Milad), election days,
  Annual Closing of Accounts. These are structurally impossible for any static dataset.

~50 rows a year. Each row should carry `source` and `verified_on` so a stale entry is
visible rather than silently trusted.

### What not to use

- **NPCI is not a practical source.** `npci.org.in` returns **403 to non-browser
  clients** (verified on `/sitemap.xml`), and it sits behind Akamai. Circulars are PDFs.
- **The RBI RTGS holiday page is stale** — it lists 2020 and earlier, because RTGS and
  NEFT went 24×7 in December 2020. Consequence worth designing around: **electronic
  transfers do not close on bank holidays; only branch counters and cheque clearing do.**
  An "is my bank open" feature must model *branch* closure, not payment-system uptime.
- **data.gov.in has no holiday dataset** — verified zero results for holiday keywords.
- **Third-party aggregators disagree with each other and are sometimes plainly wrong.**
  ClearTax's RBI page returns **410 Gone** (verified). Tallyfy's CC0 India JSON is
  **verified wrong** (Holi as 10 Mar instead of 4 Mar, Diwali 11 Nov instead of 8 Nov) —
  do not use it. Others differ on Holi (3 vs 4 Mar), Ram Navami (26/27/31 Mar), Eid, and
  Ganesh Chaturthi (14 vs 15 Sep), and some mix years. BankPulse presents the best
  third-party UX but is still a pipeline over the same sources, with no API.
- **No official RBI REST API exists** for releases or holidays (not found; not
  exhaustively disproven). `data.rbi.org.in` is statistics, not releases.

---

## 4. Recommended shape for this app

1. **RBI news**: poll `pressreleases_rss.xml` + `notifications_rss.xml` (+ `speeches`)
   **once or twice a day**, dedupe on `<link>`, store into `sources`/`events` as an
   `rss` kind. Mind the 10-item window.
2. **Holidays**: compute Layer 0 locally; refresh **HDFC's page** monthly-ish with
   conditional requests and cache in Room (`state, date, name, source, fetched_at`);
   cross-check against **RBI's matrix** for the current month; keep Layer 2 as a bundled
   JSON with provenance.
3. **Politeness**: never fetch on launch. WorkManager with daily/weekly constraints,
   `lastFetchedAt` gates, exponential backoff, a normal browser UA, ETag/
   If-Modified-Since, and serve from cache when offline. RBI 418s odd requests, NSE wants
   a browser UA, NPCI 403s — so no retry storms.

---

## 5. Verification table

**Verified live (200):** all five RBI RSS feeds (`text/xml`, 10 items each); RBI press
release, notification, speech and holiday-matrix pages; `m.rbi.org.in` matrix; NSE
holiday JSON (trading + clearing); BSE beta holiday page; HDFC holiday page (all 37
state/UT sections, including AP and the omitted UTs); KVB 2026 PDF; BankBazaar, bankifsc,
bankpulse pages.

**Verified dead or blocked:** ClearTax RBI page (410); RBI POST and unrecognised query
strings (418 WAF); NPCI from non-browser clients (403); `bseindia.com` holiday page
(Angular shell, no data).

**Verified absent:** data.gov.in holiday datasets (0 results); any RBI content JSON
endpoint; any free API that is accurate, per-state and officially licensed.

**Unverified / open:** RBI's matrix for years other than the current one (postback
blocked); whether an NPCI holiday page exists at all; a stable official DoPT 2026 PDF
URL; Calendarific/Abstract India state-level quality; the exact refresh cadence of
HDFC's page.
