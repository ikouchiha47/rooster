# Task DAG — climate dashboard

- **Status:** In progress.
- **ADR:** `docs/adrs/0013-climate-dashboard.md`
- **Goal:** a global Climate service tile, backed by cached monthly series from
  public sources. Weather stays local; Regional is a later Weather lens.
- **Rule:** resolve every join point before parallel work starts.

## Join points (must land first)

| ID | Join point | Why it blocks branching | Files |
|---|---|---|---|
| **J1** | `climate_series` schema + migration 28→29 + DAO | every adapter writes the same row shape | `ClimateEntities.kt`, `ClimateDao.kt`, `Sql.kt`, `Migrations.kt`, `AppDatabase.kt`, schema 29 |
| **J2** | Domain contract: `ClimatePoint`, `ClimateSeries`, `ClimateMetric`, `ClimateSource` | all adapters and the UI compile against it | `core/climate/Climate.kt` |
| **J3** | `ClimateRepository` + source registry + AppContainer | one owner for cache, staleness, source health | `data/ClimateRepository.kt`, `AppContainer.kt` |
| **J4** | `ClimateScreen` route/tile + `ClimateTab` shell | UI cannot be split before the route contract | `ClimateScreen.kt`, `ClimateTab.kt`, `Navigation.kt`, Home/Services tiles |
| **J5** | Metric naming, units, baselines, and source IDs | prevents two adapters inventing different IDs | `ClimateMetrics.kt` + tests |
| **J6** | `SavedRegion` + `RegionDefault` schema/repository | shared by Climate, Weather, Travel, Hazards | `RegionEntities.kt`, `RegionDao.kt`, `RegionRepository.kt`, Settings |
| **J7** | MapLibre dependency + raster style + GIBS tile source | one map engine for both regional views | `MapLibreWorldMap.kt`, `android/app/build.gradle.kts` |
| **J8** | Common `WorldMap` API (camera, gestures, locator, save) | Weather/Climate share the same viewport contract | `ui/common/WorldMap.kt` |

Only after J1–J8 compile and tests pass may the map lanes branch.

## DAG

```
J1 schema ─┐
J2 domain ─┼─> J3 repository ─┬─> A temperature adapters ─┐
J5 metrics ┘                  ├─> B ENSO adapters ────────┤
                              ├─> C CO2 adapter ──────────┤
                              ├─> D ice + sea level ──────┤
                              └─> E climate UI ───────────┤
                                                           └─> F integration + gates
J4 shell ───────────────────────────────────────────────────┘
```

### J1 — schema — DONE
- [x] `climate_series` table: source_id, metric, region, period_start,
      period_end, value, anomaly, unit, baseline, source_url, fetched_at.
- [ ] `ClimateDao`: upsert/read by metric + region, latest fetch per source.
- [ ] migration 28→29 + exported schema + migration test.

### J2 — domain — DONE
- [x] `ClimatePoint` / `ClimateSeries` as in ADR.
- [ ] `ClimateSource` interface with `id`, `metrics`, `cadence`, `licence`,
      `fetch()`.
- [ ] `ClimateSnapshot`/`ClimateDashboard` UI model.
- [ ] `ClimateMetric` seed: id, label, region, unit, baseline, source.

### J3 — repository — DONE
- [x] `ClimateRepository`: observe series, save points, last-updated, stale flag.
- [ ] source registry: one list of registered `ClimateSource`s.
- [ ] refresh policy per source; failures never blank a card.
- [ ] AppContainer wiring.

### J4 — shell — DONE
- [x] `ClimateScreen` route, Home/Services tile, `Sync` action.
- [ ] `ClimateTab` header + featured cards + placeholder states.
- [ ] cards read `ClimateDashboard`; no adapter logic in UI.
- [ ] Weather stays local; Regional is a later Weather lens.

### J5 — metric contract — DONE
- [x] IDs: `global_temp_anomaly`, `global_temp_anomaly_ncei`, `oni`, `soi`,
      `co2_mauna_loa`, `sea_ice_arctic`, `sea_ice_antarctic`, `sea_level`.
- [ ] Units and baselines are explicit per metric.
- [ ] Tests reject unknown metric/baseline pairing.

## Parallel lanes (only after J1–J5)

### A — Temperature — DONE
- [x] NASA GISTEMP CSV parser → `global_temp_anomaly` (1951–1980).
- [ ] NOAA NCEI CSV parser → `global_temp_anomaly_ncei` (1901–2000).
- [ ] Fixtures + parser tests; no network in tests.

### B — ENSO — DONE
- [x] NOAA CPC ONI ASCII parser → `oni` (1991–2020, 3-month seasons).
- [ ] NOAA CPC SOI parser → `soi`.
- [ ] Test season labels, missing values, latest period.

### C — CO₂ — DONE
- [x] NOAA GML Mauna Loa monthly CSV → `co2_mauna_loa`.
- [ ] Keep average and seasonally adjusted; label Mauna Loa, not global.
- [ ] Test header/comment handling.

### D — Ice and sea level — DONE
- [x] NSIDC monthly `N_<MM>` / `S_<MM>` CSVs → Arctic/Antarctic extent.
- [ ] NOAA STAR sea-level CSV → `sea_level`.
- [ ] Derive anomaly against 1981–2010 for ice; label sea-level reference.
- [ ] Current-month URL handling + fixture tests.

### E — UI — DONE
- [x] Featured cards with value, unit, anomaly, baseline, source, last updated.
- [ ] Sparkline from `ClimateSeries` points.
- [ ] Detail view for one series.
- [ ] Stale/error/empty states.
- [ ] No local weather, no “super El Niño”.

### E2 — chart scrubber — DONE
- [x] Card sparkline is not scrubbable; tap opens the metric detail.
- [ ] Detail chart selects the nearest real point on tap.
- [ ] Horizontal drag scrubs and snaps to real points.
- [ ] Selected point draws a dot and vertical guide; header shows period/value.
- [ ] Tapping the selected dot clears; reverting to latest.
- [ ] `LATEST` chip and tap-outside clear.
- [ ] Missing points are skipped, never interpolated.
- [ ] Vertical scroll wins vertical gestures; only horizontal drag past slop scrubs.
- [ ] Tests for nearest-point selection, toggle clear, missing values.

### G — Shared regions — DONE
- [x] `saved_regions` and `region_defaults` tables + migration 29→30.
- [x] SavedRegion/RegionDefault repository; Home default in Settings.
- [x] Manual saved views; source link fields are in the schema for Weather/Calendar.
- [x] Region controls: Home, saved views, save current view.
- [ ] Weather-place/Calendar-region source chips (source picker is the next UI slice).

### H — MapLibre regional map — DONE
- [x] Add MapLibre Native; no Google key.
- [x] Minimal style: Blue Marble base, GIBS raster overlay, coastlines.
- [x] Camera = lat/lon/zoom/bearing/pitch; no Street View/device camera.
- [x] Pinch/pan/zoom; progressive tiles; tile cache.
- [x] Locate-me only on tap; animate camera; honest denial.
- [x] Save current view; chips for Home/saved; animate to region.
- [x] Per-layer timeline from actual ranges; LATEST; clamp; no blank fake frames.
- [x] Switch Climate Regional from static image to this component.
- [x] Weather Regional reuses the same component and region store.
- [x] Weather places render as a GeoJSON circle layer, not deprecated annotations.
- [x] Fires drawn via GIBS WMS raster tiles because WMTS fires are vector-only.
- [x] Cap max zoom to 4.5 and use linear raster resampling; the remaining blocks
      are the coarse climate grid, not overzoom.
- [x] Keep longitude continuous during pan; do not snap on the antimeridian.

### I — Map tests and gates — PARTIAL
- [x] Pure camera/region tests.
- [x] Device: pan, place marker, layer change, fires, save view, locate button.
- [x] Build, unit tests, ktlint.
- [ ] Pinch-out clamp and marker-layer check after the final GeoJSON change (device disconnected).
- [ ] Offline tile cache and failure states.

### F — Integration and gates — DONE except final device check
- [x] Register all sources; no duplicate metric IDs.
- [x] `assembleDebug`, 986 unit tests, `ktlintCheck`.
- [ ] Device check when adb is available (disconnected after the zoom change).
- [x] ADR/task status update.

## Subagent boundaries

- Each lane touches **only its own adapter files + tests**; no shared schema/model edits.
- Any missing join-point contract is a stop-and-return, not a local workaround.
- Shared files (`AppContainer`, `ClimateRepository`, `WeatherScreen`) are edited by
  the orchestrator, not by parallel lanes.
- All parsers take a `String` payload; fetching/caching is outside the parser.
- Tests use fixtures, never live network.

## Definition of done for the first branch

- Global Climate shows temperature, ONI, CO₂, sea ice, sea level and detail scrubber.
- Every card shows baseline, source and last-updated.
- Climate Regional uses the shared map engine, per-layer timeline and saved regions.
- Weather Regional can reuse the same map component and region store.
- Monthly stale data is explicit; offline shows cached data.
- No IRI scraping, no forecast probabilities, no local climate claim.
- No Street View, device camera or street-level imagery.
