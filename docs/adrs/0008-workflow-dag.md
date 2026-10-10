# ADR 0008: Workflow DAG — explicit orchestration for the implicit pipeline

- Status: Proposed
- Date: 2026-10-10
- Related: `docs/adrs/0006-sources-unification.md` (one dispatch), `docs/adrs/0007-facts-relations-and-taxonomy.md` (the ladder), `docs/tasks/retag-dispatcher.md`

## The problem, with evidence

The pipeline exists, but only **implicitly**: each step calls the next, or a worker
happens to run them in order. There is no single place that states *what depends on
what*:

```
ingest (feeds/sms/weather/fx) → enrich → tag → mentions → rules
backfill: upgrade model → prune → seed → mentions → sweep rules
retag: dispatcher per family
```

Consequences already felt this session:
- **Ordering lives in code paths**, so a launch re-triggers everything and nothing
  says "seeds before mentions".
- **No resumption model.** Each worker invented its own (cursors, count gates,
  prefs flags: `rule:backfill:*`, `mentions:stopwords-cleaned-v1`, `retag:cursor:*`).
- **No single trigger abstraction**: launch, periodic WorkManager, manual buttons,
  and debug receivers each enqueue *different* units (BackfillWorker, six retag
  workers → now one dispatcher, force receivers).
- **Background vs foreground is ad hoc.** We discovered the hard way that a task
  started in the background cannot become a `dataSync` foreground service (Android
  12+) and that long background CPU work is deferred/killed. The *execution mode*
  is not modelled anywhere.

## Decision

Introduce a **workflow DAG as data** and a small **orchestrator (saga)** that runs
it, decoupling *what runs and in what order* from *what triggers it* and *how it is
executed*.

### 1. The graph is data; steps are substitutable implementations

```kotlin
// core/workflow — pure, no android
data class StepId(val value: String)
enum class Execution { FAST, LONG }              // LONG may need the foreground

data class Node(
    val id: StepId,
    val step: String,                            // a registered WorkflowStep id
    val dependsOn: Set<StepId> = emptySet(),
    val execution: Execution = Execution.FAST,
)

data class Workflow(val id: String, val nodes: List<Node>)

interface WorkflowStep {                          // like BackgroundTask, but orchestrated
    val id: String
    suspend fun run(ctx: StepContext): StepStatus // SUCCESS | SKIPPED | FAILED
}
```

A new stage is a new `Node` (data) + a `WorkflowStep` (registered). No edit to the
orchestrator or the scheduler (Open/Closed).

### 2. The orchestrator runs the graph and persists per-node state

```kotlin
class Orchestrator(
    private val steps: Map<String, WorkflowStep>,
    private val state: RunState,                  // what has completed, per run
) {
    suspend fun run(workflow: Workflow, runId: String, onProgress: suspend (TaskProgress) -> Unit): Boolean
}
```

- **Topological** execution: a node runs only when its `dependsOn` are done.
- **Resumable**: a completed node is skipped on the next run (`RunState`), so a killed
  run continues instead of restarting — the same idea as the retag cursors, unified.
- **Idempotent steps** are the contract (every step already is: cursor/count-gated).
- **Saga**: a `FAILED` node marks the run failed; because steps are idempotent and
  side-effect-light, "compensation" is *retry / skip / mark* rather than undo. Where
  a real undo is ever needed, a node pairs with a compensating step — declared on the
  node, run in reverse on failure.

### 3. Execution mode is chosen at trigger time (this is the bg/fg answer)

```kotlin
interface WorkflowTrigger { val id: String }      // launch | periodic | manual | event
interface RunExecutor {                            // WorkManager OR the foreground service
    suspend fun execute(workflowId: String, runId: String)
}
```

- a **manual/user** trigger may run LONG nodes via the **foreground service**
  (`ForegroundTaskService`, ADR-adjacent component added this session);
- a **launch/periodic** trigger runs in the **background** (WorkManager), where LONG
  nodes must still be **chunked** (a step that fits an OS window), because Android
  12+ forbids promoting a background job to a `dataSync` foreground service.

So background and foreground are the *same step code* under different executors; the
**mode is fixed when the run starts**, per Android's rule. fg→bg is trivial (the OS
takes over); bg→fg is not allowed mid-run.

### 4. Triggers are decoupled from the graph

Launch, periodic, manual, and events all do one thing: **start a run of a workflow
id**. Periodic sync stays exactly that — a *scheduled trigger* of the ingest
workflow (it is needed: ingest is inherently recurring). It is orthogonal to the DAG.

### 5. Flow control is demand-driven (backpressure, not error)

Stages **pull**; a stage runs a unit only while its downstream has **demand**. When a
downstream is saturated it advertises `demand = 0`, the upstream stage **stops being
asked** for more, and the producer simply pauses — nothing errors, nothing drops.
Demand is a **count** (à la GenStage), so "how much" is expressible, not just
"yes/no". A stall is a **paused** state, resumable from cursors, not a failure.

```
ingest ──pull──▶ tag ──pull──▶ store ──▶ derive/rules
                   └─ advertises demand N (=0 when the model is busy)
0 demand → tag stops pulling → ingest stops producing → run pauses (nobody fails)
```

Today the retag is already pull (cursor-paced) and the DRR dispatcher is demand-ish;
what is still push is **ingest**, so it needs a high-water mark to respect.

### 6. Failure is a closed, typed set — stages cannot be in an ambiguous state

Outcomes are **sum types**, so "timed out but partially applied", "null vector",
"failed but ran" are not representable:

```kotlin
// a step's outcome — no partial/unknown
sealed interface StepOutcome {
    data object Done    : StepOutcome
    data object Skipped : StepOutcome              // idempotent no-op on a done node
    data object Paused  : StepOutcome              // backpressure — retry when demand returns
    data class Failed(val kind: Failure) : StepOutcome
}
enum class Failure { TRANSIENT, PERMANENT }        // retry vs run-failed — the saga's only branch

// the resource's outcome — no null
sealed interface Inference {
    data class Ok(val vector: FloatArray) : Inference
    data object AtCapacity : Inference             // demand was 0 → the step pauses
    data object TimedOut   : Inference             // deadline blown → the step decides
}
```

Rules that make states impossible:

- **`Paused` ≠ `Failed`.** A saturated pipeline pauses and resumes; only a real error
  fails. (Backpressure is not an error.)
- **`AtCapacity`/`TimedOut` are the *step's* to interpret**, and the usual answer is
  **abstain** (`Ok` reduced) — so a busy model degrades, it does not drop the run.
- **Execution mode is fixed at construction.** A `Run` carries its `Execution`
  (`Background` | `Foreground`) and is immutable: there is no "promote bg→fg at
  runtime" state to represent, matching Android's rule.
- **Two timeouts, two levels** — the *inference* deadline (per request, in the queue,
  `setTerminate`) is separate from the *node* deadline (the orchestrator). They are
  different types and never conflated.
- **Idempotent steps + cursors make retry total**: re-running a `Done` node yields
  `Skipped`, so retry can never double-apply — the saga's only primitive is
  retry/skip/pause, and it is always safe.

## Options & tradeoffs

| option | ordering/deps | resume | add a step | cost |
|---|---|---|---|---|
| **A (chosen): DAG + orchestrator** | explicit, data | unified RunState | data entry | a coordination layer |
| B: keep implicit workers | in code | per-worker ad hoc | edit scheduling | drift, as today |
| C: WorkManager chaining (`beginWith`/`then`) | linear only | partial | re-wire | no fan-in/out, no saga |

## Blast radius

**New**
- `core/workflow/` — `Node`, `Workflow`, `StepId`, `WorkflowStep`, `Orchestrator`,
  `RunState` interface (pure; the DAG + scheduler are unit-testable).
- `data/workflow/` — `RoomRunState` (per-run per-node flags), the two `RunExecutor`s
  (WorkManager background, `ForegroundTaskService` foreground), trigger glue.

**Changed (mechanical)**
- `Straightforward steps from this session become nodes`: the DRR dispatcher
  (`RetagBackgroundTask`) is already a `BackgroundTask`; wrap it as a `WorkflowStep`.
- `BackfillWorker` body → `WorkflowStep`s (`upgrade`, `prune`, `seed`, `mentions`,
  `sweep`) with explicit edges (`seeds → mentions`).
- `FeedSyncWorker`, `SmsSyncWorker`, `EnrichWorker` → ingest `WorkflowStep`s.
- `SyncScheduler` → enqueues **runs**, not individual workers.
- `AppContainer` → registers workflows + steps + the orchestrator.

**Invariants to preserve (from the guidelines)**
- one owner per fact (steps read the store, never their own copy);
- steps stay idempotent and cursor/count-gated (that is what makes resume & saga safe);
- the DAG is data — no `when (workflowId)` in the orchestrator.

## Rollout

1. **P1** pure `core/workflow` (graph + orchestrator + `RunState`) + tests (topology,
   resume, failure).
2. **P2** wrap one existing chain as a workflow (retag) behind a feature flag; keep
   workers as fallback.
3. **P3** executors: background (WorkManager) and foreground (the FGS service); a run
   records its mode.
4. **P4** move the remaining steps (backfill, ingest) into workflows; delete the
   ad-hoc workers; triggers start runs.

## Non-goals / open questions

- Not a general-purpose engine: no dynamic DAGs, no distributed execution.
- **Compensation is decided (§6): retry / skip / pause** — there is no undo, because
  every step is idempotent and reads/writes the one store. If a step ever needs a real
  undo, it declares a compensating node; none do today.
- Cross-run state: is `RunState` per run, or per dataset with a generation (like the
  retag cursors and backfill versions)? — open.
- Where the DAG is defined: bundled data (like the taxonomy) or code? — open.
- Demand: is a stage's demand a fixed unit count or a byte/row budget? — open.

## Links

- `docs/tasks/workflow-dag.md` — the task list.
- `core/work/BackgroundTask.kt` — the step/execution contract this generalises.
- `data/service/ForegroundTaskService.kt` — the foreground executor.
