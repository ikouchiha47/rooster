package com.personalos.app.data

/**
 * One sync run, in transport-agnostic shape: feeds, SMS and gauges all report
 * the same facts, so the Events tab renders uniformly and no caller invents a
 * second record.
 */
data class SyncRun(
    val sourceId: String,
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long,
    val ok: Boolean,
    val itemsAdded: Int,
    val error: String?,
)

/** Recording a run must never break ingest: implementations swallow their own failures. */
interface SyncRecorder {
    suspend fun record(run: SyncRun)
}

object NoOpSyncRecorder : SyncRecorder {
    override suspend fun record(run: SyncRun) = Unit
}

/**
 * Room-backed recorder with bounded history. Prunes runs older than
 * [RETENTION_MS] on every record, so a silent source reads as "no recent
 * runs" rather than stale success.
 */
class RoomSyncRecorder(
    private val dao: SyncRunDao,
    private val now: () -> Long = System::currentTimeMillis,
) : SyncRecorder {
    override suspend fun record(run: SyncRun) {
        runCatching {
            dao.insert(
                SyncRunEntity(
                    sourceId = run.sourceId,
                    kind = run.kind,
                    startedAt = run.startedAt,
                    finishedAt = run.finishedAt,
                    ok = run.ok,
                    itemsAdded = run.itemsAdded,
                    error = run.error,
                ),
            )
            dao.pruneOlderThan(now() - RETENTION_MS)
        }
    }

    companion object {
        const val RETENTION_MS = 30L * 86_400_000L
    }
}
