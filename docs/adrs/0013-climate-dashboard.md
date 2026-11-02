# ADR 0013: Climate dashboard — slow signals as a separate service

- **Status:** Proposed.
- **Date:** 2026-11-02.
- **Related:** `docs/ARCHITECTURE.md`, ADR 0006 (sources), `WeatherScreen`,
  `docs/prd/0002-calendar-travel-reminders-and-incidents.md`.
- **Scope:** global and regional **climate signals**. Not local weather, not
  disaster response, not earthquake prediction.

## Context

The Weather tile currently answers one question: *what is the weather here and
now?* It reads Open-Meteo, stores daily observations, and shows a local 7-day
outlook. It has no place for the slower context that changes decisions over
weeks or seasons: global temperature, ENSO, sea ice, CO₂, sea level.

Those are different facts from weather:

- **Weather** is local, hourly/daily, forecastable, and perishable.
- **Climate** is global or regional, monthly/weekly, and meaningful as an
  anomaly against a stated baseline.
- Mixing them in one list would let a daily temperature line imply a climate
  trend, or let a global anomaly look like a local forecast.

Climate is its own service. Weather stays location-based and can later add a
**Regional** weather lens; Climate is global, slow, and monthly. They share the
atmosphere but not the time scale, the baseline, or the location contract.

```
Weather
  Weather    local now / 7-day
  Regional   map/radar/regional alerts, later

Climate
  Global     temperature, ENSO, CO₂, sea ice, sea level
  Regional   monthly anomaly map, later

News
  editorial, separate
```

A separate **Hazards** surface remains a later, different feature: fast events
(cyclones, floods, fires, quakes) are not climate signals.

## Decision

### 1. Add a `Climate` service tile

`ClimateScreen` is its own route and service tile, with a Sync action and a
global header. It is not nested under Weather and has no place selector. Weather
keeps its current local content; a Regional weather lens is a later addition,
not part of this ADR.

The first version is **global**, with a small set of regional lenses deferred.
Do not present a global series as if it were local.

### 2. Climate data is a series, not an item

A climate value is a point in a series, not an event:

```kotlin
data class ClimatePoint(
    val periodStart: Long,     // month/week/season start
    val periodEnd: Long,
    val value: Double,
    val anomaly: Double?,
)

data class ClimateSeries(
    val metric: String,        // global_temp_anomaly, oni, co2, ...
    val label: String,
    val region: String,        // "global", "nino34", "arctic", "antarctic"
    val unit: String,          // °C, ppm, million km², mm
    val baseline: String,      // "1951-1980", "1991-2020", ...
    val points: List<ClimatePoint>,
    val sourceId: String,
    val sourceUrl: String,
    val fetchedAt: Long,
)
```

Rules:

- Every anomaly carries its **baseline**; two sources with different baselines
  are not compared without normalising.
- Every series carries its **source** and **fetchedAt**; monthly data is
  expected to be stale, and the UI says so.
- A missing value is absent, never zero.
- A series is not an alert. Alerts are derived separately.

### 3. Source adapters, one metric family each

```kotlin
interface ClimateSource {
    val id: String
    val metrics: Set<String>
    val cadence: Duration
    val licence: String
    suspend fun fetch(): List<ClimateSeries>
}
```

`ClimateRepository` merges the flows, keeps the last good value, and exposes a
`ClimateDashboard` with:

- featured signals
- series for charts
- source health / last update
- optional derived summaries

A failing source degrades one card, not the whole tab.

### 4. Data storage

New table:

```sql
climate_series(
  source_id TEXT NOT NULL,
  metric TEXT NOT NULL,
  region TEXT NOT NULL,
  period_start INTEGER NOT NULL,
  period_end INTEGER NOT NULL,
  value REAL NOT NULL,
  anomaly REAL,
  unit TEXT NOT NULL,
  baseline TEXT NOT NULL,
  source_url TEXT NOT NULL,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY(source_id, metric, region, period_start)
)
```

`ClimateSeries` is rebuilt from these rows. No second copy in the UI.
Raw CSV/JSON is cached on disk for debugging and re-parse, not as the primary
read path.

### 5. Derived values belong in the domain layer

Pure functions, tested without rendering:

- anomaly from value + baseline where the source provides only raw values
- latest value, previous value, change, 12-month mean
- record rank / “warmest on record” only when the dataset supports it
- ENSO state from ONI: neutral, weak/moderate/strong/very strong El Niño or
  La Niña; **do not use “super El Niño” as an official category**
- sea-ice anomaly against 1981–2010
- sea-level trend from the series
- CO₂ growth rate

No ML and no prediction. These are arithmetic and classification.

### 6. Alerts are advisory, separate from weather alerts

Climate rules are slow and low-frequency. They must not share the weather
alert path or fire daily.

Examples:

- `ONI >= 1.5 for 3 overlapping seasons` → “strong El Niño” advisory
- `global temperature anomaly is a new monthly record`
- `Arctic sea-ice extent anomaly below -2σ for 3 months`
- `CO₂ growth rate above X ppm/year`

Rules have a **cooldown**, an explicit baseline/source, and no notification by
default. A climate advisory is a dashboard state, not an emergency push.

### 7. UI

Climate service:

- header: `Climate`, `GLOBAL · LAST UPDATED <date>`
- featured cards:
  - Global temperature anomaly
  - ENSO state (ONI + season)
  - CO₂
  - Arctic / Antarctic sea ice
  - Sea level
- each card: value, unit, anomaly, baseline, source, last updated, sparkline
- tap → detail: longer series, source, definition, baseline
- no local place selector in v1
- no fake “current temperature” from a monthly series
- explicit empty/error state per card

The tab is dense and editorial, not a dashboard of giant gauges. It reuses the
Weather tile’s header and typography.

### 7b. Chart interaction

The detail chart is a **scrubber**, not a static sparkline:

- Default state shows the **latest** point; the card header shows that value.
- Tap the line: select the nearest point, draw a dot and a vertical rule, and update
  the header to that period's value and label.
- Drag horizontally: scrub continuously, snapping to the nearest real point.
- Tap the selected dot again: clear the selection and return to the latest value.
- Tap outside the chart, or tap a `LATEST` reset chip: clear the selection.
- Missing points are skipped; the nearest valid point is selected.
- Vertical page scrolling must not be stolen: only horizontal drags past touch slop
  enter scrubbing.
- Card sparklines are not scrubbable on the card itself; tap opens the detail chart.
  This avoids fighting the card carousel/LazyColumn and keeps the target large.

The card's "original value" means the latest point, not a cached first render.

### 7c. Regional map

- Fixed map viewport with pan/zoom; the map is not the page scroll container.
- Month slider, not live data.
- Layer chips: temperature, rain, drought, sea ice, fires.
- Legend with units and baseline.
- Source and `valid for <month>` visible.
- Tap a place: show a value only when the layer has a point/region dataset;
  otherwise show coordinates, layer and source, never an invented number.
- Cache recent tiles/months; do not promise full offline global maps.
- NASA GIBS is the v1 visual source; numeric gridded anomalies (ERA5/C3S) are a
  later layer.

### 8. Data sources (first set)

These have been checked as reachable and free/public-domain or
public-with-attribution.

| Signal | Primary source | Endpoint / format | Cadence | Fields | Baseline |
|---|---|---|---|---|---|
| Global temperature anomaly | **NASA GISTEMP v4** | `https://data.giss.nasa.gov/gistemp/tabledata_v4/GLB.Ts+dSST.csv` | monthly | year, month, land-ocean anomaly | 1951–1980 |
| Global temperature cross-check | **NOAA NCEI** | `https://www.ncei.noaa.gov/access/monitoring/climate-at-a-glance/global/time-series/globe/land_ocean/1/0/1880-2026.csv` | monthly | date, departure | 1901–2000 |
| ENSO ONI | **NOAA CPC** | `https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii.txt` | monthly / 3-month seasons | season, year, total, anomaly | 1991–2020 |
| ENSO SOI | **NOAA CPC** | `https://www.cpc.ncep.noaa.gov/data/indices/soi` | monthly | standardized SOI | 1935–present |
| CO₂ | **NOAA GML Mauna Loa** | `https://gml.noaa.gov/webdata/ccgg/trends/co2/co2_mm_mlo.csv` | monthly | date, average, trend, seasonally adjusted | dry-air mole fraction |
| Arctic sea ice | **NSIDC Sea Ice Index v4** | `https://noaadata.apps.nsidc.org/NOAA/G02135/north/monthly/data/N_<MM>_extent_v4.0.csv` | monthly | year, month, extent, area | 1981–2010 |
| Antarctic sea ice | **NSIDC Sea Ice Index v4** | `https://noaadata.apps.nsidc.org/NOAA/G02135/south/monthly/data/S_<MM>_extent_v4.0.csv` | monthly | year, month, extent, area | 1981–2010 |
| Global mean sea level | **NOAA STAR / NASA altimetry** | `https://www.star.nesdis.noaa.gov/socd/lsa/SeaLevelRise/slr/slr_sla_gbl_keep_all_66.csv` | monthly | date, sea-level anomaly | TOPEX/Jason reference |

Notes:

- **GISTEMP** is the primary global temperature card; **NCEI** is a cross-check
  with a different baseline. Do not average them.
- **ONI** is the 3-month running mean; the weekly Niño 3.4 value is a different
  fact. Label which one is shown.
- **IRI/CCSR** publishes an ENSO forecast and probabilities, but the current
  public page is HTML with image plots and narrative, not a stable
  machine-readable API. The page itself now states that IRI is no longer
  providing forecast data; the IRI Data Library requires sign-in. Do not scrape
  it in v1, and do not treat image OCR as a data feed. Use CPC ONI/SOI for the
  observed ENSO state; add probabilistic forecast only through an official
  machine-readable source.
- **Copernicus ERA5/C3S** is available through the CDS API but requires an
  account/key and heavier processing. It is not needed for the first tab.
- **Berkeley Earth** is a useful cross-check but its direct S3 path changed; do
  not rely on a guessed URL.
- **Sea level** has a lag of months; display the period, not “today”.
- **Sea-ice** monthly files are per calendar month (`N_11_...` for November);
  fetch the current month file and keep the previous months from the series.
- **NOAA GML CO₂** is Mauna Loa, not a global average. Label it.
- Licences: NASA/NOAA/NSIDC are public domain or public-use; attribution is
  still shown. The app is personal, offline-first, and non-commercial.

### 9. Failure and offline behaviour

- Cache the last good series; show `LAST UPDATED <date>`.
- A failed update keeps the old series and marks the card stale; it does not
  delete the card.
- A failed source never blanks the tab.
- Monthly data is allowed to be weeks old; do not show “live”.
- The Climate tab works offline from the cache.

### 10. Explicit non-goals

- No local climate projection or downscaling.
- No earthquake/volcano prediction.
- No “super El Niño” marketing label; use official strength categories.
- No mixing global temperature anomaly with local daily temperature.
- No climate alerts as push notifications in v1.
- No Hazards/cyclone map in this ADR; that is a separate surface.

## Alternatives considered

1. **A Climate tab inside Weather.**
   Rejected: it would inherit Weather’s location contract and make a global
   monthly series look local. Climate gets its own service; Weather can later
   grow a Regional weather lens.
2. **Put climate cards on the Weather overview.**
   Rejected: it would mix local daily weather with global monthly signals and
   make both harder to read.
3. **Scrape or WebView-wrap IRI/CCSR ENSO forecast HTML.**
   Rejected: the page is image/narrative-first, IRI says it is no longer
   providing forecast data, and the Data Library requires sign-in. A WebView
   does not turn images into a series; it only adds a heavy, fragile browser.
   Use ONI/SOI now; add forecast only through an official endpoint.
4. **Use ERA5/C3S as the first source.**
   Rejected for v1: account, API key, heavy data, and processing cost. NASA/NOAA
   monthly series answer the first questions with less machinery.
5. **Store climate as generic `events`.**
   Rejected: a series point is not an event, has a baseline, and has no
   “open/closed” or severity lifecycle.

## Consequences

Positive:

- one coherent service for slow climate context
- source-specific baselines and freshness are visible
- no second copy of a climate fact
- adding a signal is a new source adapter, not a screen branch
- Weather stays local and Climate stays global; neither absorbs the other's
  cadence

Costs:

- another source/adapter surface and cache
- baseline discipline must be enforced in the UI
- monthly data will look stale by design
- the ENSO forecast is deferred until a stable feed exists; only the observed
  ONI/SOI state is in v1

## Implementation slices

1. **Schema + repository**
   - `climate_series` table and DAO
   - `ClimateSource` interface, `ClimateRepository`, stale/cache policy
2. **First three sources**
   - NASA GISTEMP
   - NOAA CPC ONI
   - NOAA GML CO₂
3. **Climate service**
   - featured cards, sparklines, source/baseline labels
   - own route and Home/Services tile; Weather stays local
4. **Cross-checks and remaining signals**
   - NOAA NCEI, NSIDC ice, NOAA sea level, SOI
5. **Advisories**
   - ONI strength, record temperature, sea-ice anomaly
   - cooldown, no push by default
6. **Later**
   - regional lens, IRI forecast if an official API exists, ocean heat,
     monsoon, other gridded datasets

## Open questions

- Should the first tab be global-only, or include a “near me” regional lens from
  gridded data? Global-only is the honest first version.
- Which sea-ice baseline: 1981–2010 or 1991–2020? Pick one and label it; do not
  mix.
- Is a monthly record card useful without a trend chart, or does it need the
  sparkline from day one?
- Do climate advisories belong in Watchers, the Weather tab, or both?
