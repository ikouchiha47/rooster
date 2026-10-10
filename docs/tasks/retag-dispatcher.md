# Task: retag dispatcher (weighted fair scheduling)

- Status: In progress
- Goal: one dispatcher chooses **which source family re-tags next**, by priority
  (weight) **and** fairness (deficit), replacing six competing workers that starve
  SMS behind the feed backlog.
- Related: `docs/adrs/0007-facts-relations-and-taxonomy.md`, `Retagger`, `RetagFamilies`

## Why

Six per-family WorkManager jobs share one embedder (concurrency 1). Whoever grabs
the lock next wins — order and share are accidental, feeds hog, SMS starves. There
is no *decider*. Also, `enqueueBackfill` bumps the version on **every** call, so
overlapping enqueues supersede in-flight workers before they finish.

## Design (SOLID)

```
core:  FamilyState(family, weight, deficit, exhausted)      pure data
       SchedulingPolicy { credit, pick, charge }            pure; new policy = new impl (OCP)
       DeficitRoundRobin : SchedulingPolicy
data:  ScheduleStore { load(): Map<String,Int>; save(...) }  interface (DIP)
       PrefsScheduleStore                                    impl over PrefsStringCache
       RetagDispatcher(policy, store, runFamily, budget)     orchestrates; a tick is bounded
       RetagDispatchWorker                                   one WorkManager job
```

Algorithm (DRR), cost = 1 chunk, quantum = 1:

```
while budget > 0 and any family not exhausted:
    credit: every non-exhausted family  deficit += weight
    loop:
        f = pick(highest deficit >= cost; ties by weight, then name)  or break
        run one chunk of f; update f.exhausted; charge: f.deficit -= 1; budget--
save deficits; return whether any family is still not exhausted
```

- **weight** → SMS (4) earns 4× credit → runs ~4× as often. *priority.*
- **deficit** → a family that just ran yields; a weight-1 feed still runs. *fairness.*
- **persisted deficit** → fairness survives across runs.

## Tasks

- [x] T1  `FamilyState` + `SchedulingPolicy` + `DeficitRoundRobin` (pure) + tests
- [x] T2  `ScheduleStore` (interface) + `PrefsScheduleStore` + fake-store tests
- [x] T3  `RetagDispatcher.tick()` + tests with fake policy/store/runFamily
- [x] T4  `RetagDispatchWorker` (one job)
- [x] T5  `SyncScheduler`: enqueue the single dispatcher; version bumps only on force (KEEP otherwise)
- [x] T6  Retire the six per-family enqueues (`RetagWorker` deleted)
- [~] T7  Build + unit tests + ktlint green. **Device check blocked** — the device's
      WorkManager/connectivity stayed flaky; could not observe a dispatcher tick.

## Tests (pure, JUnit, no Android)

| # | asserts |
|---|---|
| 1 | pick = highest deficit |
| 2 | ties break by weight, then name |
| 3 | weights 4:1 over 5 picks → ~4:1 (priority) |
| 4 | a family that just ran yields (deficit < 0) |
| 5 | weight-1 family still runs within a bounded number of picks (no starvation) |
| 6 | exhausted families skipped |
| 7 | charge debits only the chosen; credit only non-exhausted |
| 8 | dispatcher tick order matches pick; deficits persisted between ticks |

## Notes / decisions

- cost = 1 chunk (not batch-size), quantum = 1 → weights are directly "chunks per round".
- weights are data: `sms=4, rss:/gnews:/userrss:=1, weather:/fx:=1` (tunable).
- `Retagger.run(family)` and per-family cursors are **unchanged** (single responsibility).
