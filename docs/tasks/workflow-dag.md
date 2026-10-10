# Task: workflow DAG (orchestration for the implicit pipeline)

- Status: Proposed (not started)
- ADR: `docs/adrs/0008-workflow-dag.md`
- Goal: make the pipeline's ordering, dependencies, resumption and execution mode
  **explicit** — a workflow graph as data, run by a small orchestrator, triggered by
  launch/periodic/manual, executed in background or foreground.

## Why

Today ordering is implicit (workers calling workers), resumption is reinvented per
worker (cursors + prefs flags), and background/foreground is ad hoc. Adding a stage
means editing scheduling. See ADR 0008.

## Design (SOLID)

```
core/workflow :  StepId · Node · Workflow · WorkflowStep · Orchestrator · RunState   (pure)
data/workflow :  RoomRunState · RunExecutor{Background(WorkManager), Foreground(FGS)} · triggers
```

- a stage = a `Node` (data) + a `WorkflowStep` (registered) — no orchestrator edit (OCP);
- orchestrator depends on `RunState` + a step map (DIP);
- steps stay idempotent + cursor/count-gated (makes resume & saga safe);
- **execution mode is fixed at trigger time** (Android: no bg→fg promotion).

## Tasks

- [ ] W1  `core/workflow`: `StepId`/`Node`/`Workflow` + topological plan (pure) + tests
- [ ] W2  `Orchestrator.run` over a `RunState` interface + tests (order, resume, failure)
- [ ] W3  `RoomRunState` (per run × per node completion) + migration + test
- [ ] W4  Wrap the retag as a `WorkflowStep` (reuse `RetagBackgroundTask`/dispatcher)
- [ ] W5  Executors: `BackgroundExecutor` (WorkManager) + `ForegroundExecutor` (FGS)
- [ ] W6  Triggers: launch / periodic / manual start a *run*, not individual workers
- [ ] W7  Move backfill steps into a `catchup` workflow (explicit edges: seeds→mentions)
- [ ] W8  Move ingest (feeds/sms/enrich) into an `ingest` workflow
- [ ] W9  Delete the ad-hoc workers once their workflows are live
- [ ] W10 Typed outcomes: `StepOutcome` (Done|Skipped|Paused|Failed{TRANSIENT|PERMANENT})
      + `Inference` (Ok|AtCapacity|TimedOut) — no partial/null states
- [ ] W11 Demand/backpressure: a `Demand` count; a `Paused` stage resumes from cursors;
      an ingest high-water mark (the push→pull change)
- [ ] W12 Execution fixed at construction: a `Run` carries `Background`|`Foreground`;
      `LONG` nodes in a `Background` run must be chunked
- [ ] W13 Build + tests + ktlint green; device check

## Tests (pure, JUnit)

| # | asserts |
|---|---|
| 1 | a node runs only after all `dependsOn` are done |
| 2 | independent nodes may run in either order (plan is a valid topology) |
| 3 | a completed node is skipped on the next run (resume) |
| 4 | a FAILED node fails the run; downstream nodes do not run |
| 5 | a cycle in the graph is rejected at construction |
| 6 | progress is reported in node-completion units |

## Q&A captured in the ADR

- **Trigger**: buttons/enqueues start a **run of a workflow** (e.g. `catchup`), not a
  single task. Launch, periodic, and manual are just different triggers of the same run.
- **Periodic sync**: still needed — periodic ingest is a scheduled trigger of the
  `ingest` workflow, orthogonal to the DAG.
- **bg ↔ fg**: same step code under different executors; **mode chosen at run start**.
  fg→bg is fine; **bg→fg is not allowed** (Android 12+), so a LONG step in a
  background run must still be chunked.
- **Busy / timeout**: the *resource* returns a typed outcome (`AtCapacity`/`TimedOut`),
  the *step* interprets it (usually **abstain**), and only the *saga* retries or fails.
  **`Paused` is not `Failed`** — saturation pauses, it does not drop the run.
- **Backpressure**: demand-driven, à la GenStage — a stage pulls only while downstream
  has demand; demand 0 stops the producer; resume from cursors. No error, no drop.
- **Impossible states**: outcomes are closed sums; execution mode is fixed at
  construction; idempotent steps make retry of a `Done` node a `Skipped` — never a
  double-apply.
