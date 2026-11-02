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

Only after J1–J5 compile and tests pass may the lanes branch.

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

### J1 — schema
- [ ] `climate_series` table: source_id, metric, region, period_start,
      period_end, value, anomaly, unit, baseline, source_url, fetched_at.
- [ ] `ClimateDao`: upsert/read by metric + region, latest fetch per source.
- [ ] migration 28→29 + exported schema + migration test.

### J2 — domain
- [ ] `ClimatePoint` / `ClimateSeries` as in ADR.
- [ ] `ClimateSource` interface with `id`, `metrics`, `cadence`, `licence`,
      `fetch()`.
- [ ] `ClimateSnapshot`/`ClimateDashboard` UI model.
- [ ] `ClimateMetric` seed: id, label, region, unit, baseline, source.

### J3 — repository
- [ ] `ClimateRepository`: observe series, save points, last-updated, stale flag.
- [ ] source registry: one list of registered `ClimateSource`s.
- [ ] refresh policy per source; failures never blank a card.
- [ ] AppContainer wiring.

### J4 — shell
- [ ] `ClimateScreen` route, Home/Services tile, `Sync` action.
- [ ] `ClimateTab` header + featured cards + placeholder states.
- [ ] cards read `ClimateDashboard`; no adapter logic in UI.
- [ ] Weather stays local; Regional is a later Weather lens.

### J5 — metric contract
- [ ] IDs: `global_temp_anomaly`, `global_temp_anomaly_ncei`, `oni`, `soi`,
      `co2_mauna_loa`, `sea_ice_arctic`, `sea_ice_antarctic`, `sea_level`.
- [ ] Units and baselines are explicit per metric.
- [ ] Tests reject unknown metric/baseline pairing.

## Parallel lanes (only after J1–J5)

### A — Temperature
- [ ] NASA GISTEMP CSV parser → `global_temp_anomaly` (1951–1980).
- [ ] NOAA NCEI CSV parser → `global_temp_anomaly_ncei` (1901–2000).
- [ ] Fixtures + parser tests; no network in tests.

### B — ENSO
- [ ] NOAA CPC ONI ASCII parser → `oni` (1991–2020, 3-month seasons).
- [ ] NOAA CPC SOI parser → `soi`.
- [ ] Test season labels, missing values, latest period.

### C — CO₂
- [ ] NOAA GML Mauna Loa monthly CSV → `co2_mauna_loa`.
- [ ] Keep average and seasonally adjusted; label Mauna Loa, not global.
- [ ] Test header/comment handling.

### D — Ice and sea level
- [ ] NSIDC monthly `N_<MM>` / `S_<MM>` CSVs → Arctic/Antarctic extent.
- [ ] NOAA STAR sea-level CSV → `sea_level`.
- [ ] Derive anomaly against 1981–2010 for ice; label sea-level reference.
- [ ] Current-month URL handling + fixture tests.

### E — UI
- [ ] Featured cards with value, unit, anomaly, baseline, source, last updated.
- [ ] Sparkline from `ClimateSeries` points.
- [ ] Detail view for one series.
- [ ] Stale/error/empty states.
- [ ] No local weather, no “super El Niño”.

### F — Integration and gates
- [ ] Register all sources; no duplicate metric IDs.
- [ ] `assembleDebug`, unit tests, `ktlintCheck`.
- [ ] Device check when adb is available.
- [ ] ADR/task status update.

## Subagent boundaries

- Each lane touches **only its own adapter files + tests**; no shared schema/model edits.
- Any missing join-point contract is a stop-and-return, not a local workaround.
- Shared files (`AppContainer`, `ClimateRepository`, `WeatherScreen`) are edited by
  the orchestrator, not by parallel lanes.
- All parsers take a `String` payload; fetching/caching is outside the parser.
- Tests use fixtures, never live network.

## Definition of done for the first branch

- Duration: ~2–3 weeks of one-person work.
- `Weather > Climate` shows global temperature, ONI, CO₂, sea ice, sea level.
- Every card shows baseline, source and last-updated.
- Monthly stale data is explicit; offline shows cached data.
- No IRI scraping, no forecast probabilities, no local climate claim.
