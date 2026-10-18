# Job scheduler + HTTP client — design plan

Two general-purpose modules, built for the whole app (not just enrichment):

- **`core/jobs/`** — a job/workflow executor: run a set of work items with
  bounded concurrency, retry, timeouts and per-run metadata. Enrichment is the
  *first* job type; rules-in-background, inference and other processing register
  on the same core.
- **`core/net/`** — an HTTP client built for this project: a **dynamic rate
  limiter** and a **circuit breaker**, per host.

They compose but are independent: a job need not do HTTP.

---

## `core/net/` — HTTP client (limiter + circuit breaker)

Pure logic + one thin platform transport.

```kotlin
data class Request(val url: String, val headers: Map<String,String> = emptyMap(), val timeoutMs: Int)
data class Response(val status: Int, val headers: Map<String,String>, val body: String)

/** The only platform piece: perform the request. */
fun interface HttpTransport { suspend fun execute(req: Request): Response }

/** Facade. Owns the limiter + breaker; surfaces status and Retry-After. */
class HttpClient(transport, limiter: HostLimiter, breaker: CircuitBreaker) {
    suspend fun get(url: String): HttpResult          // Ok(Response) | RateLimited(retryAfter) | Open(circuit)
    suspend fun getText(url: String): String?         // convenience, keeps today's call sites working
}
```

- **`HostLimiter` (dynamic, pure) — the genuinely new piece.** Per host: a
  concurrency window + cooldown. Starts high; **AIMD** — additive increase on
  success, multiplicative decrease on the **first** 429/503; `Retry-After`
  honoured literally. `acquire(host)` / `onSuccess(host)` / `onLimited(host, retryAfter)` / `onError(host)`.
- **Circuit breaker — REUSE the existing one; do not write a second.**
  `core/work/FailurePolicy.kt` already implements exactly this rule (K
  consecutive transient — or one permanent — open the circuit for a cooldown;
  a success closes it) with the pure `CircuitBreaker`, `FamilyHealth` and the
  `HealthStore` port (`PrefsHealthStore`). Key it by **host** instead of family
  and plug it into the client. (The names read as retag vocabulary —
  `FamilyHealth`/`HealthStore` — so a cheap rename to `Health`/`HealthStore`
  makes the reuse honest; the algorithm does not change.)
- **Transport** = `HttpURLConnection` (no new dependency; the app is
  disk-constrained). One implementation behind `HttpTransport` so tests drive a
  fake and a later swap is a new adapter.

The app's current `core/net/Http.kt` (`getText`, status swallowed) is **replaced**
by this — the fetch call sites move to `HttpClient`.

---

## `core/jobs/` — the scheduler

```kotlin
enum class Order { TIME_DESC, ID_DESC }

/** Immutable, versioned. Adding a job = a new registration; the core is untouched. */
data class JobDefinition(
    val id: String,               // "enrich", "retag", "infer"
    val version: Int,
    val order: Order,
    val pageSize: Int,
    val timeoutMs: Long,
    val maxAttempts: Int,
    val step: suspend (WorkItem) -> StepResult,   // Success | Retry(afterMs) | Skip | Fail
)

object JobRegistry { fun of(id: String): JobDefinition? }   // the registration process

data class WorkItem(val id: Long, val payloadKey: String)   // cursor row
data class JobRun(val id: Long, val jobType: String, val defVersion: Int, val status: RunStatus,
                  val nErrors: Int, val cursorTs: Long?, val cursorId: Long?,
                  val attempted: Int, val ok: Int, val failed: Int,
                  val createdAt: Long, val updatedAt: Long, val finishedAt: Long?)

/** Bounded concurrency. The producer hands the next keyset page to an idle slot. */
class WorkerPool(val size: Int) { suspend fun drain(job: JobDefinition, cursor: Cursor): RunReport }
```

- **Cursor pagination, newest-first** is the work source. Each run starts at the
  latest head and the cursor records *where the last run ended*:
  ```sql
  WHERE url IS NOT NULL
    AND enriched_at IS NULL          -- membership comes from enrichment STATUS,
    AND LENGTH(TRIM(content)) < 40   -- so a re-walk is idempotent
    AND id <= :ceiling               -- freeze this run's membership snapshot
    AND (timestamp, id) < (:cur_ts, :cur_id)
  ORDER BY timestamp DESC, id DESC
  LIMIT :n
  ```
  `:ceiling` is set once per run (= current max `events.id`), so new arrivals can
  never collide with this run's pages — the next run picks them up with a fresh
  ceiling. The composite `(timestamp, id)` keyset disambiguates equal timestamps
  so no row is skipped. A page sets `cursor := (min ts, min id)`; an empty page
  ends the drain.
- **Retry** = `onError` per `RetryPolicy`: a `Retry(afterMs)` from the step
  schedules the item again (bounded by `maxAttempts`); `Skip` marks it done;
  `Fail` marks it failed. Saga-shaped: **per item**, atomic — never cross-item.
  A 429/503 is *not* a failure: it backs off and re-queues (never
  `markEnrichmentAttempted`); only real errors (404, timeout, unparseable) mark
  attempted. Rows that already carry a description are never candidates, so the
  drain never re-fetches or overwrites them.
- **Ports** (implemented in `:app`): `JobRunStore` (runs), `JobStateStore`
  (per-item + per-`job_type` key/value).

---

## Adapters (`:app`)

- `data/jobs/EnrichJob` — the first `JobDefinition` (`id="enrich"`). Its source
  is the cursor page over `content < 40 AND enriched_at IS NULL`; its step
  fetches the article via `HttpClient`, extracts the summary
  (`core/enrich/ArticleSummary`), writes `events.content`. Rate-limit → `Retry`.
- Later, registered on the same core: `RuleJob` (rules over new items),
  `InferJob` (the embedding batcher), `RetagJob`.
- `EnrichWorker` → triggers a `job_runs` run for `enrich` and drains (moves to
  the FGS when it exceeds WorkManager's window).

---

## DB (migration v22)

```sql
CREATE TABLE job_runs(
  id            INTEGER PRIMARY KEY,
  job_type      TEXT NOT NULL,         -- "enrich", "retag", "infer"
  def_version   INTEGER NOT NULL,      -- JobDefinition version that ran
  status        TEXT NOT NULL,         -- awaiting | processing | success | failure
  n_errors      INTEGER NOT NULL DEFAULT 0,
  cursor_ts     INTEGER,               -- resume position: the last end
  cursor_id     INTEGER,
  attempted     INTEGER NOT NULL DEFAULT 0,
  ok            INTEGER NOT NULL DEFAULT 0,
  failed        INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,      -- when the run was scheduled
  updated_at    INTEGER NOT NULL,      -- progress heartbeat; gaps = stuck runs
  finished_at   INTEGER);              -- NULL until terminal

CREATE TABLE job_type_state(          -- limiter + breaker state, persisted for debugging
  job_type TEXT, key TEXT,            -- key = "host:thehindu.com"
  value TEXT, updated_at INTEGER,
  PRIMARY KEY (job_type, key));
```

Run lifecycle: `awaiting` (enqueued/follow-up created, not started) → `processing`
(`doWork` picked it up; `updated_at` is the heartbeat, refreshed per committed
page) → `success` / `failure` (terminal, `finished_at` set). `n_errors` counts
page/item failures along the way — a run can succeed *with* errors. `created_at`
vs `updated_at` separates queue wait from progress.

A killed run stays `processing` forever, so one rule closes the loop: a new run
for the same `job_type` flips any stale `processing` row (heartbeat older than N
minutes) to `failure` ("superseded", `n_errors+1`) and resumes from
`max(stored cursor)` — exactly one live run stays queryable, and the flip is the
audit trail of the interruption. Per-item `enriched_at` marks make the resume
itself idempotent.

`value` JSON e.g. `{"concurrency":12,"cooldownUntil":…,"state":"open","hits":3,"lastStatus":429,"last429At":…}` —
so later we can answer *which host rate-limited us, when, at what concurrency*.

Per-item state stays on `events.enriched_at` (no new column).

---

## Where the existing pieces go

| today | becomes |
|---|---|
| `core/net/Http.kt` | the `core/net/` client (limiter + breaker) |
| `ArticleEnricher.enrichBatch` | the `enrich` job's `step` (+ a `HostLimiter`) |
| `EnrichWorker` | the `enrich` run trigger + drain |
| `SyncScheduler` enrich cadence | 6 h → 1 h; drain to the FGS when long |

Wake-up semantics (who runs when): WorkManager is the clock, the module is the
executor, the FGS is the long-run host. The periodic `EnrichWorker` (KEEP, its
own unique name) is the heartbeat *and* backstop; a drain that ends with work
remaining chains a one-time follow-up `EnrichWorker` under a separate unique name
(`"enrich-drain"`, `APPEND_OR_REPLACE` so parallel triggers serialize instead of
piling up) carrying the cursor — so the chain continues across windows instead of
waiting for the next tick. If a hop would exceed the ~10-minute worker window it
hands to `ForegroundTasks.start("enrich")` instead of chaining (one cursor owner
at a time; the FGS writes the same `job_runs` row). A killed FGS is never
restarted (`START_NOT_STICKY`, by design); the periodic worker resumes from the
committed cursor.

A page (20–50 HTTPS fetches) is the unit of failure: progress commits per page,
so a death loses at most one page, and limiter/breaker decisions stay per
page per host.

## Rollout

- **P1** — `core/net/` (dynamic limiter + breaker, unit-tested) and the `enrich`
  job draining the ~3k backlog. This is the first consumer and the proof.
- **P2** — register a **second** job (rules or inference) to prove the core is
  general without touching it.

## Open

- Worker pool size + AIMD constants (start 24, ceiling 32, floor 1) — tune with
  the limiter tests, not a guess.
- Whether the breaker + limiter state persist per host or per `job_type`+host
  (shown above as the latter, so enrich/infer keep separate budgets).
