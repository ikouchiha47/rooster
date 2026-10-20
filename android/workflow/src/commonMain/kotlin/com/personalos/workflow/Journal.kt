package com.personalos.workflow

/** What an event marks: work beginning or ending. A power sample rides on the END. */
enum class JournalPhase { START, END }

/**
 * One line of the observability log — deliberately **append-only** and separate
 * from the ledger.
 *
 * The ledger is the authority for *state* (what may resume, who owns a run); this
 * is the record for *reading later*: how long a node took, whether it failed, and
 * a device power sample taken across the same window. It is allowed to be lossy
 * and to be dropped on a retention boundary — the ledger never is.
 *
 * A `START` with no matching `END` is how a crash looks in an append-only log.
 */
data class JournalEvent(
    val at: Long,
    /** `workflow` for an orchestrated run, `stage` for a one-node host. */
    val host: String,
    val runId: String,
    val node: String,
    val attempt: Int,
    val phase: JournalPhase,
    val status: NodeStatus? = null,
    /** Known only on END. */
    val durationMs: Long? = null,
    /** The failure's own words, when a step supplied one. */
    val error: String? = null,
    /** On END when sampled: whole-device energy drawn across the window. */
    val energyNwh: Long? = null,
    /** On END when sampled: charge drawn across the window — the fallback many phones have. */
    val chargeUah: Long? = null,
    /** On END when sampled: length of the power window. */
    val elapsedMs: Long? = null,
)

/**
 * The observability port. Optional by design: the workflow runs exactly the same
 * with [Noop], so telemetry can never change what the engine does.
 */
fun interface Journal {
    suspend fun record(event: JournalEvent)

    companion object {
        val Noop: Journal = Journal { }
    }
}
