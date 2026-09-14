# Place inference signals — research

Status: **in progress.** Everything in §2 was verified directly against the downloaded
data on 2026-09-14. §3 is **pending** the second research pass; nothing there is
confirmed yet, and it is labelled as such rather than guessed.

Related: `plans/0001-data-sources-places-and-alerts.md` §1 (the place model and the
gazetteer decision), `ARCHITECTURE.md` §4.2 (News ranking) and §9 (unowned work).

---

## 1. The problem

News and SMS items must be resolved to a place **offline, with no geocoding API**
(Open-Meteo's geocoder was tested and rejected: "Bangalore" → Pakistan,
"Berhampore" → New Zealand, because it has no country scoping).

The primary signal is a bundled gazetteer. This document covers the **secondary
signals** — evidence for items that never name a place directly.

They are not one matcher. They are **weighted evidence** feeding a resolver:
a gazetteer hit is strong, a named institution or minister is medium, currency and
script are weak tiebreakers. When nothing clears the confidence bar the item falls
back to **country or worldwide** rather than guessing wrong.

---

## 2. Verified: already available for free

All of the following came out of the GeoNames India dump already downloaded for the
gazetteer, so they need **no new sourcing**.

### 2.1 The dump itself

| File | Size | Contents |
|---|---|---|
| `IN.zip` → `IN.txt` | 15.7 MB (69.6 MB unpacked) | 660,026 India places, tab-separated, 19 columns |
| `admin1CodesASCII.txt` | 148 KB | canonical state/UT names |
| `admin2Codes.txt` | 2.3 MB | canonical district names |
| `alternatenames/IN.zip` | 1.4 MB | India-only aliases (the global file is 195 MB — do not fetch it) |

Place counts by feature class (measured):

| Class | Count | Use |
|---|---|---|
| `A/ADM1` | 36 | states / UTs |
| `A/ADM2` | 763 | districts |
| `A/ADM3` | 6,891 | sub-districts / tehsils |
| `P/PPLA`, `P/PPLA2`, `P/PPLA3` | 35 / 148 / 24 | state and district seats |
| `P/PPL` | 546,134 | everything, down to hamlets |
| **`S/RSTN`** | **8,486** | **railway stations** |
| **`L/LCTY`** | **6,974** | **localities / neighbourhoods** |

`P/PPL` is unusable raw. Filtering by population gives a shipping size that is
tiny after gzip:

| Population threshold | Places | Raw | Gzipped |
|---|---|---|---|
| ≥ 5,000 | 14,142 | 1.8 MB | **0.65 MB** |
| ≥ 10,000 | 12,599 | 1.6 MB | **0.59 MB** |
| ≥ 20,000 | 10,682 | 1.4 MB | 0.51 MB |

**Recommendation:** admin levels + `PPL ≥ 10,000` ⇒ ~12.6k places at **0.59 MB
gzipped**, plus railway stations and localities filtered by parent district.

### 2.2 Currency → country — a free signal

`countryInfo.txt` (31 KB) carries a **`CurrencyCode`** column. India's row:

```
country=IN  capital=New Delhi  CurrencyCode=INR  phone=91
languages=en-IN,hi,bn,te,mr,ta,ur,gu,kn,ml,or,pa,as,bh,sat,ks,ne,sd,kok,doi,mni,sit,sa,fr,lus,inc
```

So the user's case — "only one currency is mentioned and there is no location
information" — is solved by bundled data with zero new sourcing.

**Symbols need no dataset either.** `countryInfo.txt` has `CurrencyCode` and
`CurrencyName` but **no symbol column**, so symbols looked like a gap. They are
not: the platform already has them. Verified with `jshell` on JDK 21:

```
INR en-IN : ₹        INR en-US : ₹        INR name : Indian Rupee
USD en-US : $        EUR de-DE : €        GBP en-GB : £
total currencies: 230
```

`java.util.Currency.getSymbol(locale)` / `getDisplayName(locale)` covers all 230
currencies on-device. For Indian news text the practical aliases are `₹`, `Rs`,
`INR` and "rupees", which is a tiny list to add on top.

**Demonyms** ("Chinese", "American", "Bangladeshi") are the one real gap here —
country *names* are covered by GeoNames alternates, but demonyms are not. The
likely source is **Wikidata `P1549` (demonym)**, which every country carries.
Pending confirmation in §3.

### 2.3 Script / language → region

`countryInfo.txt` lists each country's languages (§2.2 above), and the Unicode block
ranges per Indian script are static constants. Together these give script-detection →
language → likely states, again with no external source.

### 2.4 Vernacular aliases — with one caveat

Alias counts actually present for India, per language:

| lang | aliases | lang | aliases |
|---|---|---|---|
| `hi` | 1,441 | `kn` | 500 |
| `ta` | 929 | `or` | 462 |
| `ur` | 860 | `ml` | 360 |
| `bn` | 824 | `pa` | 265 |
| `mr` | 705 | `as` | **83** |
| `te` | 632 | `gu` | 585 |

**Assamese is thin (83)** — this independently confirms the plan doc's finding that
Assam, Karnataka and Odisha are under-served by Google News editions and must lean on
direct outlet feeds.

### 2.5 The Wikidata bridge — the most useful find

**6,173 India places already carry a Wikidata QID** in the alternatenames file:

```
geonameid=1114942  wikidata=Q26777380
geonameid=1252653  wikidata=Q2484053
```

Every hard secondary signal — ministers, institutions, exam bodies — is the kind of
thing Wikidata models well, and Wikidata is the only realistic free source that is
continuously maintained. Because the gazetteer already keys into that graph, those
signals are reachable **without inventing a new identity scheme**.

GeoNames licence: **CC BY 4.0**, attribution satisfied by an in-app link to
`geonames.org` (an About/Licences screen).

---

## 3. Verified: semi-static entity lists

Sourced 2026-09-14. The headline result is uncomfortable: **almost none of these
come free.** Wikidata is the right spine for *identity*, but it is materially
incomplete for current rosters and region mappings, so most of this needs a small
curated or periodically-snapshotted table.

### 3.1 Politicians / ministers -> constituency or state

The first research pass reported "only 22 of 1,046 current Lok Sabha members carry
a constituency". **Re-checked directly and that number is misleading** - it measured
the wrong item. Wikidata stores constituency on the **term-specific** position, not
the generic one:

| position item | statements | with `P768` (constituency) | coverage |
|---|---|---|---|
| `Member of the 18th Lok Sabha` (`Q125498038`) | 79 | 74 | **94%** |
| `Member of the Lok Sabha` (`Q16556694`, generic) | 1,109 | 65 | **5.9%** |

Verified on a real person: Narendra Modi (`Q1058`) carries `P768 = Varanasi` on his
*16th* and *17th* Lok Sabha statements and **none** on his generic
`Member of the Lok Sabha` statement - which is precisely why the naive query looked
catastrophic.

So **where the term-specific item is used, the constituency is 94% reliable.** But
it is used for only **79** people in a 543-seat house, roughly 15%, and the generic
item that most members use is 6% populated. Neither alone yields a full roster.

**Conclusion — a maintained snapshot is required, for a corrected reason.** Sources,
best first:

- **Wikipedia "List of members of the 18th Lok Sabha"** - one wikitable per state
  (the state is the section heading, not a column). CC BY-SA 4.0, continuously
  updated. Best free starting point.
- **Rajya Sabha members with a State column** - MyNeta/ADR (HTML) or Wikipedia.
  MyNeta's compilation has **no stated open licence** - verify before shipping.
- **Union Council of Ministers** (~70-80 people): the PMO page is a news article,
  so **hand-curate** the cabinet.

**Join key: Wikidata QID.** It is the only stable cross-source identifier - MyNeta
`candidate_id` is per-election, ECI constituency codes change on delimitation.

**Do not** scrape `sansad.in`, `services.india.gov.in` (Who's Who) or
`results.eci.gov.in`: all JS-rendered, no open licence, and the frontends call
internal endpoints that are not public.

**Other countries are the same shape** - position items exist per country, keyed by
QID, so nothing here is India-specific.

### 3.2 Country demonyms - free, do first

**Wikidata `P1549` (demonym)** covers **202 countries** in English (CC0):
India->Indian, USA->American(s), China->Chinese, Bangladesh->Bangladeshi. This
closes the demonym gap, which matters because news text names countries by
nationality. Supplement with **`mledoze/countries`** (ODbL-1.0) for `altSpellings`
and name variants.

### 3.3 Institutions -> country/state - curate, seeded from Wikidata

Wikidata has **6,214** Indian entities carrying both `P17` (country) and `P159`
(headquarters). Spot-checks: RBI->Mumbai, SEBI->Mumbai, Supreme Court->New Delhi,
IIM Ahmedabad->Ahmedabad all correct; **IIT Bombay has no `P159`** - patchy even for
famous bodies. No official downloadable Indian institution registry exists.
**Effort: ~150-300 curated rows**, seeded from Wikidata and hand-checked: RBI, SEBI,
NPCI, Supreme Court, NITI Aayog, ISRO, IITs, IIMs, major banks, PSUs, regulators.
Changes are rare, so a static bundle is fine.

### 3.4 Festivals -> region - curate the region, compute the dates

Two separate problems:

- **Region: no dataset.** Of 652 India festival items on Wikidata, `P131`/`P276`
  coverage is almost nil - Onam->Kerala works, but **Diwali, Durga Puja, Bihu and
  Ganesh Chaturthi carry no location**. Labels also collide (Onam is also a Korean
  *eup*; Bihu an Ecuadorian watercourse). **Hand-curate ~50-100 rows**: Onam->Kerala,
  Pongal->Tamil Nadu, Durga Puja->West Bengal, Bihu->Assam, Ganesh
  Chaturthi->Maharashtra.
- **Dates: compute, do not look up.** Zero Indian festival items carry date
  properties. Use **`python-holidays`** (MIT) - an `India` class with **36
  subdivisions** plus Hindu/Islamic/Persian calendars - and **pre-generate a static
  table at build time**. Verified caveat: Hindu dates are only assured for
  **2001-2035**, and Islamic dates are moon-sighting approximations. JS alternative:
  `date-holidays` (ISC + CC-BY-3.0, Islamic dates 1970-2080).

### 3.5 Exam names -> region - curate (~40 rows)

No exam->geography dataset exists anywhere. Almost all are national (->India); only
state PSCs and state boards are state-specific. **Curate ~40 rows**, versioned by
academic year: NEET-UG/PG, JEE Main/Advanced, UPSC CSE/CDS/NDA, CAT, GATE, CLAT,
CUET, UGC-NET, SSC CGL/CHSL/MTS, IBPS PO/Clerk, RRB NTPC/Group D, plus state PSCs
(TNPSC, KPSC, APPSC, MPPSC, BPSC, UPPSC) and state boards. Exams are added, renamed
and retired - CUET only appeared in 2022 - so this needs an annual review.

### 3.6 Sports teams -> city/state - curate (~36 rows)

Wikidata league membership works (`P118`), but **HQ (`P159`) is patchy**: of the IPL
teams only Kolkata Knight Riders has an HQ recorded. Team names are largely
self-locating ("Mumbai Indians"), so **hand-curate ~10 IPL + ~14 ISL + ~12 PKL** -
more reliable and far smaller than querying.

### 3.7 Language -> state - curate a ~30x36 matrix

Wikidata `P37` on "state of India" covers only ~19 modern states/UTs (missing
Arunachal Pradesh, Bihar, Gujarat, Himachal Pradesh, Manipur, Mizoram, Nagaland,
Sikkim, Tripura, Delhi and most UTs). Seed from the Wikipedia "official languages of
Indian states" table and the Eighth Schedule, then curate. Combined with the Unicode
script ranges (S2.3) this gives a strong script+language -> state prior.

### 3.8 Railway stations and localities - already free

Confirmed straight from the dump: `S/RSTN` = **8,486** ("railroad station - a
facility comprising ticket office, platforms, etc."), **0 with an empty name and 0
with empty coordinates**, and every row carries GeoNames admin1/admin2 codes so
state and district are derivable. `L/LCTY` = **6,974** ("locality - a minor area of
indefinite boundaries"); `L/LCLY` = **0** in India. Both CC BY 4.0.
Weight stations **below** populated places (names are noisy - strip the
"Junction"/"Railroad Station" suffix); LCTY is weaker still and collides with
district and state names.

---

## 4. Rankings

### 4.1 Evidence strength at inference time

How much a single hit should move the answer, strongest first:

1. **Place name or alias hit** - city > district > state > country
2. **Ancestor inheritance** - naming a district matches every place under it
3. **Explicit administrative mention** - "West Bengal government", "Odisha police"
4. **Institution** named (RBI, Supreme Court) -> country/state
5. **Railway station** named -> its district (strip the "Junction" suffix)
6. **Politician** named -> constituency/state (§3.1)
7. **Exam / festival** named -> region (§3.4, §3.5)
8. **Script / language** -> likely states (§2.3, §3.7)
9. **Currency** mentioned alone -> country (§2.2)
10. **Fallback** -> country, then worldwide. Never a wrong guess.

Multiple countries named means the item belongs to **each** named country **and** to
worldwide.

### 4.2 Build order (coverage per unit of effort)

**Free, already in hand - just index it:**

1. **Country demonyms and name aliases** (§3.2) - a tiny file that fixes nationality
   mentions, which is how news names countries.
2. **Railway stations and localities** (§3.8) - 8,486 + 6,974, already in the dump.
3. **Currency** (§2.2) - already in `countryInfo.txt`; symbols from
   `java.util.Currency` with no data at all.
4. **Script ranges** (§2.3) plus the curated **language -> state** matrix (§3.7).

**Small curation (tens of rows):**

5. **Exam aliases** (§3.5) - ~40 rows.
6. **Sports teams** (§3.6) - ~36 rows.

**Medium curation (hundreds of rows, or a build step):**

7. **Festival region map** plus pre-generated dates (§3.4).
8. **Institutions** (§3.3) - ~150-300 rows.

**A pipeline, not a dataset:**

9. **Politicians / ministers** (§3.1) - a snapshot refreshed per election cycle,
   keyed on Wikidata QID.

**Avoid:** scraping `sansad.in`, `services.india.gov.in`, the PMO page or
`results.eci.gov.in` (JS-rendered, no open licence, ToS-risky); relying on Wikidata
for MP->constituency or festival->region; MyNeta as an automated feed.

---

## 5. Open questions

- **Vendor or curate?** A Wikidata subset means a build-time extraction step (SPARQL
  against a pinned dump); curated TSVs are small but need manual refresh. On the
  evidence in §3, curation wins wherever the source is thin anyway.
- **Who refreshes the politician snapshot, and how is a reshuffle noticed?** A manual
  "refresh now" action plus a periodic prompt is the honest minimum.
- **Where does the "worldwide" bucket live** - a tier, a tag, or both?
- **Do we record unmatched mentions?** An unrecognised capitalised phrase may be a
  place we do not have (a new name, or a transliteration variant). Recording misses
  is how the gazetteer would grow from real data rather than guesswork.
- **Islamic festival dates are approximations by nature** - is a +/- 1 day window
  acceptable for rules?


---

## 6. Beyond location — this is a general mention layer

Worth stating explicitly, because it changes the design: **location inference is
one consumer of a much larger capability**, not a feature in its own right.

Everything above is dictionary matching against a gazetteer of *named things*.
That machinery is not location-specific. From the same news text we could extract:

| Extractable | Also enables |
|---|---|
| **People** (ministers, officials, executives) | who-said-what, affiliation, the network graph |
| **Organisations** (ministries, companies, courts, agencies, unions) | sector, HQ, country, ownership |
| **Places** | location ranking, the place hierarchy |
| **Treaties / agreements / frameworks** (Paris Agreement, RCEP) | trade and treaty tracking |
| **Legislation, bills, acts** | regulatory timelines |
| **Commodities and trade flows** | prices, M&M relevance |
| **Event types** (election, summit, protest, disaster) | rules, alerts |
| **Currencies with a direction** ("rupee falls") | M&M |
| **Quotes → attribution** | who claimed what |
| **Quantities with units** (deaths, ₹, %) | severity ranking |

If that is the target, the storage shape should be **`mentions`**, not a
`place_id` column on an event:

```
mentions(item_id, surface_form, kind, entity_id?, confidence, span)
```

Co-occurrence over `mentions` is what produces a network graph. A `place_id`
column cannot be extended into that later without a migration.

**The realistic offline approach is the same one we already chose for places:**
match text against **labels and aliases** from a dictionary, rather than trying to
run a general NER model on-device. That keeps it deterministic, testable and free,
and it degrades gracefully — an unmatched mention is simply not recorded.

**Scope is not India-limited.** Wikidata is global and country-agnostic, so
"other countries' ministers" is the *same* extraction, not a second one: a
position like "Minister of Finance" exists per country, with qualifiers for
jurisdiction and dates, all keyed by QID. That shared identity scheme is precisely
what would make a cross-country trade/treaty graph possible.

