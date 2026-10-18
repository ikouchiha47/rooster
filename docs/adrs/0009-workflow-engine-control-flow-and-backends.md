# ADR 0009: Workflow engine — graph as data, control flow, multiplatform backends

- **Status:** Proposed.
- **Date:** 2026-10-18.
- **Supersedes:** ADR 0008 (its **execution and control-flow model** only; its diagnosis
  stands and is reused below).
- **Related:** `docs/plans/jobs-and-http.md` (a single-stage job becomes a leaf step
  here), `docs/tasks/workflow-dag.md` (the task list, retargeted), `:mlsdk` (the
  precedent for a module that is lifted out and reused unchanged).

## Why a new ADR

ADR 0008 diagnosed the real problem — the pipeline is *implicit*, so ordering lives in
code paths, resumption is reinvented per worker (cursors, count gates, prefs flags), and
background/foreground is ad hoc. It proposed a DAG-as-data + orchestrator, and that part
is right. It then **scoped out** two things we now need:

1. It said *"not a general-purpose engine: no dynamic DAGs"* and modelled dependencies
   as a single `dependsOn: Set<StepId>`. We want an explicit, small **control-flow
   vocabulary** — serial, fork/join, if/else — resolved over a **NetworkX-shaped graph**
   (toposort, successors/predecessors), Orkes/Prefect-style workflow-as-code.
2. It assumed one Android module. We want the engine to be **a separately shippable,
   multiplatform module**: Android **and** everything else (JVM, iOS, native), with no
   dependency on `:app` or Room.

So: keep 0008's diagnosis, typed-outcome discipline and demand model; replace its
execution/control-flow scope and its packaging.

## Decision

### 1. A Kotlin Multiplatform module, `:workflow`, shippable on its own

- `android/workflow/`, package `com.personalos.workflow` — **not** `com.personalos.app`,
  so it carries no app identity and can be published and reused (the `:mlsdk` precedent).
- Targets: `androidTarget()`, `jvm()`, and `iosX64/iosArm64/iosSimulatorArm64()`.
  `commonMain` is pure Kotlin + `kotlinx-coroutines-core`; nothing else.
- Published with its own group/artifact/version (`maven-publish`). Consumers depend on
  `:workflow`, never the reverse. The engine must **not** import Room, Android, WorkManager
  or anything from `:app`; persistence is a port the host implements (§6).

### 2. The graph is data, and it is NetworkX-shaped

```kotlin
// :workflow commonMain — no platform types
@JvmInline value class NodeId(val value: String)

enum class NodeKind { TASK, FORK, JOIN, BRANCH }

data class Node(
    val id: NodeId,
    val kind: NodeKind,
    val step: String? = null,        // registered step id, for TASK
    val predicate: String? = null,   // registered predicate id, for BRANCH
)

/** A directed graph. The NetworkX surface we actually use, nothing more. */
interface WorkflowGraph {
    val nodes: Set<NodeId>
    fun successors(id: NodeId): List<NodeId>
    fun predecessors(id: NodeId): List<NodeId>
    fun outDegree(id: NodeId): Int
    fun inDegree(id: NodeId): Int
    fun topologicalOrder(): List<NodeId>   // Kahn; throws on a cycle
    fun validate()                          // acyclic + fork/join well-formed
}
```

`topologicalOrder()` is **Kahn's algorithm** and is the dependency resolver. It is also
the *validation* gate: a cycle, a dangling edge, or a `JOIN` without matching `FORK`
breadth is rejected at construction, never at run time.

### 3. Control flow is exactly two constructs — fork/join and if/else

The only structured control beyond a plain edge:

- **fork/join** — a `FORK` node has out-degree ≥ 2; a `JOIN` node has in-degree ≥ 2 and
  is a **barrier**: it enables when *all* incoming branches reach it. Branches run in
  parallel. `JOIN` without a `FORK`, or branch arity mismatch, is rejected by `validate()`.
- **if/else** — a `BRANCH` node holds a predicate id and has exactly two outgoing edges,
  tagged `THEN` / `ELSE`. At run time the predicate picks one; the untaken subgraph's
  nodes are marked `SKIPPED` (never `FAILED`, never silently absent).

Serial is just an edge. There is no loop, no sub-workflow call, no dynamic graph
mutation — which is what keeps this a *small* engine (see Non-goals).

**Static shape, dynamic selection.** The graph is fixed at build time; only *branch
selection* is a run-time decision. That is the reconciliation of "toposort" (static,
whole-graph) with "if/else" (run-time): toposort gives a valid order and levelisation;
branching decides which of those nodes are actually *live* in a given run.

### 4. Status is a closed set; sets of status are bitmasks

Per-node-run status is **one** value, so `when` stays exhaustive:

```kotlin
enum class NodeStatus { PENDING, RUNNING, SUCCEEDED, SKIPPED, RETRY_EXHAUSTED, FAILED, PAUSED }
```

Where a *set* of statuses is the question ("which statuses count as terminal?"), use a
bitmask so the sets are expressible and composable with `or`/`xor`:

```kotlin
object Statuses {
    const val LIVE      = 1 shl NodeStatus.RUNNING.ordinal
    const val TERMINAL  = (1 shl NodeStatus.SUCCEEDED.ordinal) or
                          (1 shl NodeStatus.SKIPPED.ordinal) or
                          (1 shl NodeStatus.FAILED.ordinal) or
                          (1 shl NodeStatus.RETRY_EXHAUSTED.ordinal)
    fun isTerminal(mask: Int) = (mask and TERMINAL) != 0
}
```

A run's outcome is 0008's `StepOutcome`/`Failure` idea, kept: `SUCCEEDED`/`SKIPPED`/
`PAUSED`/`FAILED{TRANSIENT|PERMANENT}`, so "timed out but partially applied" is not
representable.

### 5. One engine; a **backend** decides how a run is hosted (the multiplatform seam)

The engine (graph walk, status, retry/skip/pause) is identical on every platform. What
differs is *how a scheduled run is handed to the OS for durability*:

```kotlin
/** The only platform-varying piece: how a run (or a node) is scheduled and survives. */
interface WorkflowBackend {
    /** Host one run of [workflowId]: durable, may survive process death. */
    suspend fun submit(run: WorkflowRun)
    /** Run it inline now (foreground/manual, or platforms with no durable host). */
    suspend fun executeNow(run: WorkflowRun): RunResult
}
```

- **Android** → `WorkManagerBackend`: `enqueueUniqueWork(runId, …)` + a
  `CoroutineWorker` that drives the engine. Durable across process death, Doze and
  reboot; constraints; backoff. This is the only place WorkManager appears.
- **Everything else (JVM, iOS, native)** → `CoroutineBackend`: runs the engine inline in
  a coroutine scope. iOS uses this until (if) iOS ships its own backend
  (`BGTaskScheduler`); adding that later is **a new `WorkflowBackend` implementation**,
  with the engine untouched (Open/Closed).

So "Android + Everything Else" is answered by *one* interface implemented twice — the
same rule 0008 already used for background/foreground executors, generalised to platforms.

### 6. Persistence and triggers are ports, not dependencies

- `RunStore` — per-run, per-node status (the resumable state). `:workflow` defines the
  interface; `:app` supplies a Room implementation; another consumer supplies its own.
- `StepRegistry` / `PredicateRegistry` — `String` id → implementation, filled by the host
  at startup. Adding a step is **registration, not an engine edit** (OCP).
- Demand/backpressure (0008 §5, GenStage-shaped: a stage pulls only while its downstream
  advertises demand > 0) is kept as the flow-control model.

### 7. First consumer — the enrich→retag pipeline

The two-stage pipeline we were designing is **the first workflow**, and it is the linear
special case (a fork/join with one branch):

```
ingest ─▶ [fork] ─ enrich(batch) ─(success ∪ retry_exhausted)─▶ [join] ─▶ retag(batch)
```

- Stage 1's pending is owned by stage 1 (`url IS NOT NULL AND enriched_at IS NULL AND
  length(trim(content)) < 40` — already `Sql.ENRICHMENT_CANDIDATES`).
- Stage 2's pending is owned by stage 2 (`EmbedText.stateOf(...) == PENDING`).
- The join edge moves only the resolved items forward — "only the needed ones move".

A **single-stage job** (the `docs/plans/jobs-and-http.md` `JobDefinition`/cursor drain)
becomes a leaf `TASK` under this engine rather than a parallel mechanism; `core/net`'s
limiter/circuit-breaker is a *capability a step uses*, orthogonal to the engine.

## Options & tradeoffs

| option | control flow | platforms | add a stage | cost |
|---|---|---|---|---|
| **A (chosen): KMP engine + backend seam** | fork/join + if/else, toposort | Android + JVM + iOS | register a node + edge | a real engine to maintain |
| B: ADR 0008 as written | `dependsOn` only, no branches | Android only | data entry | no branches; not shippable |
| C: WorkManager `beginWith/then` | linear only | Android only | re-wire each time | no fan-in/out, no branches, no resume |
| D: vendor KMPWorker | DAG, but in-process + unwired limiter | Android + iOS (no JVM) | bento of beta APIs | durable parts are a rewrite (§research) |

## Blast radius

**New**
- `:workflow` module — `commonMain` (graph, toposort, control flow, engine, status,
  ports), `androidMain` (`WorkManagerBackend`), tests in `commonTest`/`jvmTest`.
- `:app` `data/workflow/` — `RoomRunStore`, the step/predicate registrations
  (`enrich`, `retag`), trigger glue.

**Changed (later phases)**
- `EnrichWorker`/`RetagDispatchWorker` become triggers/hosts of a workflow run.
- `SyncScheduler` enqueues **runs**, not individual workers.
- `docs/plans/jobs-and-http.md`'s `JobDefinition` becomes a leaf `WorkflowStep`.

## Rollout

1. **P1** `:workflow` pure engine: graph + toposort + fork/join + if/else + status +
   engine + tests (JVM). No Android, no Room.
2. **P2** backend seam: `CoroutineBackend` (common) + `WorkManagerBackend` (android).
3. **P3** `:app` `RoomRunStore` + the enrich→retag workflow as the first run; feature-flag
   it beside today's workers.
4. **P4** move backfill/ingest onto the engine; delete the ad-hoc workers.

## Non-goals

- No distributed execution; single-process, single device.
- No dynamic graph mutation at run time (branches select static paths; they do not build
  new nodes).
- No loops/sub-workflow calls in P1 (a loop is a step that iterates internally).
- Not a UI: this is an execution engine; screens read `RunStore` through a UI model.

## Open questions

- **Where the graph is defined:** bundled data (like the taxonomy) or Kotlin builders? —
  a `WorkflowBuilder` DSL (`sequence {}`, `parallel {}`, `ifElse {}`) that *compiles to*
  the graph IR is the likely answer; both views exist, one IR.
- **Granularity of durability:** one WorkManager work per **run** (engine drives nodes) or
  per **node**? Per-run is simpler and matches 0008; per-node survives mid-run death
  better. Start per-run.
- **iOS host:** `CoroutineBackend` only, or a `BGTaskScheduler` backend later? — later.
- **`RunStore` scope:** per run, or per dataset with a generation (like the retag
  cursors)? — same open question 0008 left; start per run.
- **Toolchain:** `iosArm64`/`iosSimulatorArm64` targets require the Kotlin/Native
  toolchain + Xcode locally. The module **declares** them; the everyday gate builds
  `android` + `jvm`, and iOS compiles on a machine with Xcode.

## Links

- ADR 0008 — the diagnosis this supersedes in scope.
- `docs/tasks/workflow-dag.md` — the task list (retargeted by this ADR).
- `docs/plans/jobs-and-http.md` — a single-stage job as a leaf step; the HTTP limiter.
- `:mlsdk` — precedent for a separately shippable module.
