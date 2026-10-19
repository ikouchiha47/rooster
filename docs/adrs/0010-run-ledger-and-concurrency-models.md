# ADR 0010: The run ledger and the two concurrency models

- Status: Proposed
- Date: 2026-10-19
- Supersedes: `0008-workflow-dag.md`; the data model and `§6 RunStore` of `0009-workflow-engine-control-flow-and-backends.md`
- Related: `docs/ARCHITECTURE.md` §10–§11, §11.5

## Summary

The engine is a correct DAG **executor** but an invalid **run store**. It records a
node only *after* the node settles, so the store can never answer what was
*attempted*, by whom, or when — which is the one job a durable orchestrator has.
This ADR keeps the executor and replaces the record: **write-ahead intent**, one
row per *attempt*, a thin **run** row for identity/ownership, `invoked_by` for
provenance, and **derived** run status. It also names the two concurrency models
the app actually has, and makes them a *hosting* choice over one schema.

## The problem, with evidence

Measured against the code as it stands:

1. **Outcomes are written after the work.** `Engine.run` runs a whole level, awaits
   it, *then* writes each node (`store.put`). So a kill mid-level leaves **no row**
   for any node in that batch — recovery cannot distinguish "never started" from
   "started, unknown", and a non-idempotent step silently double-applies.
2. **No `RUNNING` node exists**, so "what is in flight" is unanswerable, always.
3. **No attempt is recorded.** A node that fails transiently three times and
   succeeds stores one row: `SUCCEEDED`. Retry history, stuck detection and
   "is this the 3rd try" are inexpressible.
4. **No ownership.** Nothing records which host is driving a run, so two schedulers
   can both walk it. (This is live today: `EnrichWorker` runs the workflow while
   `RetagDispatchWorker` also retags — two owners for one fact.)
5. **`max(id)` is insertion order, not event order.** The retag cursor is a row id,
   so a backfilled item (old event, new row) is either treated as new or skipped.
6. **No provenance.** A node row cannot say what invoked it, so a step used by two
   flows (retag) cannot be attributed.

Calling 1–4 "minimalism" was wrong: minimalism reduces *surface*, not *invariants*.

## Decision

### 1. The invariants — non-negotiable, however small the store is

- **I1 — intent before effect.** A row exists before the work runs.
- **I2 — every attempt is attributed.** owner + `started_at`, so a stale lease is decidable.
- **I3 — the outcome is durable**, against the attempt it belongs to.

### 2. One row per attempt; the run row is thin

```
runs(
  run_id PK, workflow_id, definition_version,
  owner, lease_expires_at,            -- ownership only, not status
  started_at )

run_nodes(
  run_id, node_id, attempt,           -- PK(run_id, node_id, attempt)
  status, owner, lease_expires_at,
  started_at, finished_at,
  invoked_by, correlation_id,         -- provenance (see §4)
  inputs, outputs )                   -- references, never payloads
```

`inputs`/`outputs` are handles — a cursor, a count, a range, a cache key. Storing
payloads would give a fact two owners and is explicitly out (see Non-goals).

### 3. Status is derived; the run row is a lease holder

**One writer per fact.** Executions own outcomes; the run row owns *ownership*.

```
run status = fold over executions sharing a run_id
  any FAILED | RETRY_EXHAUSTED -> FAILED
  any PAUSED                   -> PAUSED
  all terminal                 -> SUCCEEDED
  otherwise                    -> RUNNING
```

Derivation cannot express "RUNNING before any node starts" or "who owns the run",
so those two facts stay in the thin `runs` row. `finished_at` is the max of the
nodes' `finished_at` — derived, not stored.

### 4. `invoked_by` and `correlation_id` are properties of the *execution*

A step is invoked by many flows (`retag` by the workflow and by the dispatcher), so
"which flow" is many-to-many and cannot be a property of the node. On the attempt:

```
invoked_by      schedule:<id> | workflow:<runId> | manual | event:<name>
correlation_id  the run id, or the trigger id
```

This gives Model 1 provenance (below) and makes double-ownership **visible in data**:
`retag` appearing with two `invoked_by` values *is* the bug from §1.4.

### 5. The write-ahead sequence

```
claim run            -> runs: owner=me, lease=now+ttl
for each level:
    for each node in the batch (parallel):
        insert attempt n: PENDING            <- I1, before any work
        update attempt n: RUNNING, inputs    <- before side effects
        execute
        update attempt n: terminal, outputs  <- I3
derive run status from the attempts
release the lease
```

### 6. Recovery is a table, not a guess

| store state | meaning | action |
|---|---|---|
| no row | never attempted | run it |
| RUNNING, lease live | in flight, owned elsewhere | leave it |
| RUNNING, lease expired | attempted, outcome unknown | re-run as attempt n+1 |
| `SUCCEEDED`/`SKIPPED` | done | skip |
| `FAILED`/`RETRY_EXHAUSTED` | done, bad | fail the run |

### 7. The two concurrency models are a *hosting* choice

A **stage is a one-node workflow**. The models differ in who hosts, not in schema
or steps:

```
        steps (shared, host-agnostic)     host                        ledger
        EnrichStep / RetagStep  ──────►   StageHost      (Model 1) -> (nothing written)
                                          WorkflowHost   (Model 2) -> runs + run_nodes
```

- **Model 1 — stage/poller.** A worker calls the step directly; its state is its own
  cursor in the app store. **No run row, no node row.** The ledger is untouched.
- **Model 2 — orchestrated run.** Claim, walk, write the ledger.

`StageHost` and `WorkflowHost` are bound once at composition (the `Enrichment` seam
is the precedent), so the choice is per pipeline and nothing migrates.

**Progress differs, and that is not cosmetic:**

- Model 1's work set is **open-ended** (`max(ulid) − cursor` moves as items arrive),
  so a ratio can *go backwards*. It shows `last-run` + `backlog` as a number. **No bar.**
- Model 2's work set is **fixed at claim time**, so `done/total` is a true fraction.
  A bar is honest.

```
Model 1   idle | running      last-run 12:04   backlog 128       (no bar)
Model 2   RUNNING             enrich ▓▓▓▓▓▓░░ 14/20              (bar)
```

### 8. Cursors are ULIDs, not row ids

A cursor must be **event-ordered**. `id` is insertion order, so a backfilled item
gets a high id and the walk mis-orders it. ULID is time-ordered and sortable, and
the app already uses it (`EventEntity.ulid`).

**Condition:** the ULID must encode **event time**, not ingest time — otherwise it
is arrival order with more bytes. For feeds, take the 48-bit prefix from the item's
published date where present.

This changes the *step's cursor* (Model-1 state), not the ledger:
`Retagger.cursorKey`, `Sql.EVENTS_MAX_ID_FOR_PREFIX`, `Sql.EVENTS_BEFORE_ID`.

### 9. OCC and leases

The claim and the transition are single statements, so no lock is needed:

```sql
-- claim: create, or take over a non-terminal run whose lease is free
INSERT OR IGNORE INTO runs(...) VALUES(...);
SELECT changes();                                   -- 1 = we own it

-- take over an abandoned run (OCC + lease in one statement)
UPDATE runs SET owner = ?, lease_expires_at = ?, version = version + 1
WHERE run_id = ? AND version = ?
  AND (owner IS NULL OR lease_expires_at < ?);

-- node transition, guarded the same way
UPDATE run_nodes SET status = ?, ...
WHERE run_id = ? AND node_id = ? AND attempt = ? AND version = ?;
```

`version` on `runs`/`run_nodes` is the OCC counter; every accepted write bumps it.

## Contracts & Interfaces

```kotlin
data class RunState(
    val runId: String,
    val workflowId: String,
    val definitionVersion: Int,
    val owner: String?,          // null = free
    val leaseExpiresAt: Long?,
    val startedAt: Long,
)

enum class NodeStatus { PENDING, RUNNING, SUCCEEDED, SKIPPED, RETRY_EXHAUSTED, FAILED, PAUSED }

data class Attempt(
    val runId: String,
    val node: NodeId,
    val attempt: Int,
    val status: NodeStatus,
    val version: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val invokedBy: String,
    val correlationId: String,
    val inputs: String?,         // references
    val outputs: String?,
)

interface RunStore {
    suspend fun claim(runId: String, workflowId: String, definitionVersion: Int, owner: String, now: Long): Boolean
    suspend fun release(runId: String, owner: String, now: Long)
    suspend fun beginAttempt(attempt: Attempt): Boolean      // I1
    suspend fun finishAttempt(runId: String, node: NodeId, attempt: Int, version: Long, status: NodeStatus, outputs: String?, now: Long): Boolean  // I3 + OCC
    suspend fun attempts(runId: String): List<Attempt>
    suspend fun run(runId: String): RunState?
}
```

`Engine` gains a `now: () -> Long` and an `owner: String`, and its walk is unchanged
except for the write-ahead calls. `RunStatus` gains `RUNNING`.

## Functional Scope

| ID | Requirement | Verification |
|---|---|---|
| REQ-RUN-01 | A node row shall exist before its step's side effect runs. | store read inside a fake step |
| REQ-RUN-02 | Two hosts shall not both drive one run. | two concurrent claims; one wins |
| REQ-RUN-03 | A node interrupted mid-flight shall be re-runnable as attempt n+1. | kill + expiry + re-claim |
| REQ-RUN-04 | A stale OCC write shall be refused. | write with an old version |
| REQ-RUN-05 | Run status shall be derived, never stored. | fold over attempts |
| REQ-RUN-06 | Every attempt shall record `invoked_by` and `correlation_id`. | row assertion |
| REQ-RUN-07 | A Model-1 stage shall write no ledger rows. | store empty after a stage run |
| REQ-RUN-08 | A cursor shall order by event time. | backfilled old item is not skipped |
| REQ-RUN-09 | The run row shall not duplicate status. | no status column in `runs` |

## Test plan — both models

Written with the implementation; `:workflow:jvmTest` (in-memory + SQLite via
`JdbcSqliteDriver`) and the app's `testDebugUnitTest` for the app-side hosts.

**Model 2 — orchestrated**
1. `claimIsIdempotent` — first claim true, second false; the second derives.
2. `intentIsWrittenBeforeWork` — the step reads the store and sees its own `PENDING`/`RUNNING` row (I1).
3. `attemptIsRecordedPerTry` — a transient-failing step leaves attempts 1..n.
4. `interruptedAttemptIsRetried` — lease expires → attempt n+1; settled nodes skipped.
5. `staleOccWriteIsRefused` — `finishAttempt` with an old version returns false.
6. `runStatusIsDerived` — FAILED if any attempt failed; SUCCEEDED when all terminal; RUNNING otherwise.
7. `twoHostsCannotDriveOneRun` — concurrent claims; exactly one proceeds.
8. `provenanceIsRecorded` — `invoked_by`/`correlation_id` on every attempt.
9. `killMidLevelRerunsOnlyTheUnsettled` — pre-seed a level; only the rest re-runs.
10. `forkJoinBarrier` — a join waits for its live predecessors (the first real fork).

**Model 1 — stage/poller**
11. `stageWritesNoLedgerRows` — run a stage; `runs` and `run_nodes` stay empty.
12. `cursorAdvancesAndResumes` — a kill mid-walk resumes from the cursor.
13. `cursorOrderIsEventTime` — an item with an older event time but a newer row is not skipped or double-processed (the ULID property).
14. `stageIsAttributable` — an execution records `invoked_by = schedule:<id>`.

**Both**
15. `sqliteAndInMemoryAgree` — the same scenario against `InMemoryRunStore` and `SqlDelightRunStore`.

## Non-goals

- No event log, no deterministic replay (not Temporal).
- No payload storage; `inputs`/`outputs` are references only.
- No node-level versioning; the **workflow** is versioned, the run records it.
- Not a new scheduler; `WorkflowBackend` stays a lifecycle port (submit/cancel/observe).
- Not migrating existing `workflow.db` data (run state is disposable).

## Rollout

1. `runs`/`run_nodes` schema + `RunStore` port (write-ahead) + `InMemoryRunStore`, with the Model-2 tests.
2. `SqlDelightRunStore` + the SQLite e2e tests; `Engine` write-ahead calls; derived status.
3. ULID cursors in `Retagger` + `RetagDao`/`Sql` (independent of 1–2; unblocks Model-1 ordering).
4. Hosts: `StageHost` (Model 1) and `WorkflowHost` (Model 2) behind one seam.
5. The run/step status UI reads the ledger.

## Links

- Supersedes: `docs/adrs/0008-workflow-dag.md`, `docs/adrs/0009-workflow-engine-control-flow-and-backends.md`
- Module: `android/workflow/`
- App hosts: `app/src/main/kotlin/com/personalos/app/data/workflow/`
