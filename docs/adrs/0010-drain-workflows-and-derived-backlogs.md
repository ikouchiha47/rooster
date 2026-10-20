# ADR 0010: Drain workflows — one node per stage, backlog derived from item facts

- **Status:** Proposed.
- **Date:** 2026-10-20.
- **Related:** ADR 0009 (workflow engine), ADR 0003 (rules and the store), ADR 0006
  (sources), `docs/adrs/0009-workflow-engine-control-flow-and-backends.md`.

## Context

Background work has become a set of drains: **enrichment** (fetch an article body for a
feed item whose description is empty), **retagging** (re-run the active tagger after a
model/taxonomy change), **launch backfill** (seeds/partitions), and **rule backfills**
(one rule over history). They keep reinventing the same mechanics:

- a backlog query;
- a bounded batch;
- a chain while backlog remains;
- a periodic safety net;
- unique-work names, retry/backoff and network constraints;
- a status row in Watchers.

Two correctness problems surfaced:

1. **The retag cursor cannot see enrichment.** It walks item ids once per tagger
   version. An item enriched after that walk has tags (and a semantic vector) computed
   from its old short description. The current `enrich-retag` workflow only sequences
   one bounded retag batch, and the standalone dispatcher knows nothing about
   enrichment.
2. **The Alerts surface and the drain surfaces were conflated.** A rule match is not an
   alert. Matches belong to Watchers (per rule) and Radar (the timeline); Messages owns
   SMS, so its Alerts are SMS matches only. That is a separate decision, but it shares
   the same “who owns which fact” rule.

We rejected two alternatives:

- a new `item_stages` table: correct, but another table to migrate and keep in sync;
- a content hash/queue table: implicit, and still a second fact to maintain.

## Decision

### 1. One one-node workflow per stage

`AppWorkflows` defines two independent workflows:

```kotlin
fun enrich(): Workflow = workflow(ENRICH) { task(ENRICH_NODE, ENRICH) }
fun retag(): Workflow = workflow(RETAG)  { task(RETAG_NODE, RETAG) }
```

`enrich-retag` is removed as a correctness mechanism. It may remain as an optional
manual/bulk action, but no drain depends on its edge. The workflow tables record run and
node status for the **batch**; they do not store per-item state.

### 2. The DB is the queue; no MQ

WorkManager schedules; the item tables are the backlog. Each drain does:

- one **count** query for depth/metrics (Watchers, adaptive batch size);
- one **batch** query with `LIMIT` and a deterministic `ORDER BY`.

No event bus, no listener, no in-memory queue. Steps are **idempotent** and
**at-least-once**: a crash after the write but before the node completes is replayed.

### 3. Retag discovers enrichment through existing facts

No new table. The existing columns are the state:

| derived state | predicate |
|---|---|
| retag `PENDING` | no `tagged_items` row for the **active** tagger |
| retag `STALE` | `events.enriched_at > tagged_items.tagged_at` |
| retag `DONE` | `tagged_items.tagged_at >= events.enriched_at` |
| enrich `PENDING` | `events.enriched_at IS NULL`, URL present, body short |
| enrich `FAILED` | `events.enriched_at = 0`, `enrich_attempts` exhausted |

The retag backlog is one query:

```sql
SELECT e.*
FROM events e
LEFT JOIN tagged_items ti
  ON ti.item_id = e.ulid AND ti.tagger_id = :activeTagger
WHERE ti.item_id IS NULL
   OR e.enriched_at > ti.tagged_at
ORDER BY e.timestamp DESC, e.id DESC
LIMIT :batch
```

`TagWriter` writes the `tagged_items` marker on every successful tag, including an empty
tag set, and `markAll(REPLACE)` refreshes `tagged_at`. Enrichment writes `enriched_at` in
the same transaction as the new body. No code calls retag; the next retag query sees the
new timestamp.

A new tagger version is the same query: the active tagger has no markers.

### 4. Ordering

- **Enrichment:** `enrich_attempts ASC, timestamp DESC` — fresh news first; retries after
  fresh items.
- **Retag:** mismatch first, `timestamp DESC` — user-visible items first.
- **Count:** same predicates without `LIMIT`; used for depth and optional adaptive batch
  sizes.

### 5. Shared drain code

Extract the scheduling/worker mechanics into one place:

```kotlin
data class DrainSpec(
    val uniqueName: String,
    val label: String,
    val group: String = uniqueName,
    val batch: Int,
    val chainDelaySeconds: Long,
    val reconMinutes: Long?,
    val network: NetworkType,
    val countKey: String?,
    val singular: String?,
    val plural: String?,
)

interface DrainTask {
    suspend fun step(): DrainOutcome   // processed, more
    suspend fun backlog(): Int         // count query
}
```

`DrainScheduler` owns `enqueueNow`, `enqueueFollowUp`, `ensurePeriodic`, the
`REPLACE-unless-running` policy, backoff and network constraints. `DrainWorker` owns
`setProgress`, retry/failure mapping, chain-if-more and the periodic safety net.

The enrichment and retag workers become thin hosts for their `DrainTask`; backfills can
adopt the same shape later.

### 6. Ownership of effects

- Enrichment owns **content + `enriched_at` only**. It does not retag, refresh vectors or
  call rules.
- Retag owns **tags + the vector + the marker**. It re-embeds from the current text, so
  an enriched item gets a vector for the enriched body, not the old description.
- Rules remain evaluated on ingest and on enrichment (`reevaluateEnriched`). Moving rules
  onto a drain needs a per-item evaluation marker; that is out of scope for this ADR.

The previous `refreshSemanticVectors` call in the enrichment path is removed; retag is
the single writer of tags and vectors.

### 7. Triggering: sync starts enrichment only

On a completed source sync (`FeedSyncWorker`, `SmsSyncWorker`, or
`FeedIngestor.onLanded`) the only direct trigger is **enrichment**:

```
sync → enqueue enrichment → update content + enriched_at
                                      ↓ (derived)
                          retag backlog query sees the new timestamp
```

Retag is **not** started by the sync, and no ids are passed. It discovers the enriched
items through the predicate in §3 on its own recon (30 min) and chains while the backlog
is non-empty. A tagger-version change still enqueues retag immediately; that is a
different trigger, not a sync.

An enrichment batch may enqueue a retag **nudge** (a WorkManager enqueue, no payload) when
it changed content, to cut latency. Losing that nudge is harmless: the next retag recon
finds the same rows. It is a scheduling hint, never the correctness mechanism.

If a sync lands no items, enrichment is not enqueued; the retag recon still runs for a
version backlog. The former `enrich-retag` edge is not used on this path.

### 8. Watchers registration is explicit

Each `DrainSpec` is a `WorkerJobSpec`; `scheduledJobs()` is built from the specs. A new
drain appears in Watchers by registering its spec, not by editing a screen.

## Code changes needed

| Area | Change |
|---|---|
| `AppWorkflows` | add one-node `enrich()` and `retag()`; remove `enrichRetag()` or keep only as manual |
| `AppContainer` | register `ENRICH` and `RETAG` workflows; `WorkflowEnrichment` runs `ENRICH` only |
| `WorkflowSteps` | `RetagStep` uses the derived backlog, not the version cursor alone |
| `RetagDao` / `Sql` | add `itemsNeedingRetag(activeTaggerId, prefix, limit)` and a count; keep the old cursor query only if a version sweep still needs it |
| `Retagger` | replace the id-cursor walk with the backlog query; keep family prefixes/DRR if fairness is still wanted |
| `ArticleEnricher` / `EventDao` | newest-first candidates; `DEFAULT_BATCH` 40; keep the failed-attempt cap |
| `TagWriter` | marker `tagged_at` must refresh on every tag (already `REPLACE`); vectors written here |
| `AppContainer` | delete `refreshSemanticVectors` and the `onEnriched` vector call; enrichment only updates content |
| `SyncScheduler` | move enrichment/retag scheduling to `DrainScheduler`; periodic recon = 30 min for enrichment |
| `WorkerJobs` | add specs for the two drains; group/registration |
| `EnrichWorker` / `RetagDispatchWorker` | become `DrainWorker` hosts |
| `WorkflowRuns` | one-node workflows; no retag edge |
| SQL/indexes | `events(timestamp)` already exists; add `events(enriched_at)` (or a partial index) if the enrichment count is slow; `tagged_items` PK covers the join |
| Tests | retag picks an enriched item (`enriched_at > tagged_at`); marker missing after a version bump; marker fresh → excluded; enrichment order newest-first; idempotent replay; count vs limit; Watchers specs |

## Consequences

- Enrichment and retag **converge through data**, not through calls: no dependency to
  carry, no cross-worker wiring.
- No new table; the existing `enriched_at`/`tagged_at` timestamps are the state.
- A retag can run before an enrichment commits; the next pass sees the newer
  `enriched_at` and re-tags. At-least-once is safe because tagging is idempotent.
- The workflow tables stay batch-run history; they are not per-item state.
- No per-item stage row means “depth” is a count query. That is acceptable; an MQ or a
  stage table would be the alternative if per-item observability becomes a requirement.
- Clock-based comparison is the weak point: `enriched_at > tagged_at` assumes the wall
  clock is monotonic. Tagging and enrichment run on one device; a backwards clock can
  delay a retag, not corrupt it.

## Non-goals

- An MQ/event bus.
- A per-item workflow run.
- An `item_stages` table or content hash.
- Re-evaluating rules after retag (needs its own marker; separate decision).
- Changing the Messages/Alerts SMS-only boundary (separate surface decision).
