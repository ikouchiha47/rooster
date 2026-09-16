package com.personalos.app.data

import com.personalos.app.core.sources.SourceSpecs
import com.personalos.app.core.tag.Ulid
import com.personalos.app.data.work.SyncScheduler
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of the sources fact (ADR 0003, CODE-DESIGN-GUIDELINES.md §1).
 *
 * Everything source-shaped flows through here: the seeder inserts, Settings will
 * write, the ingestor reads. Seeded rows (`seeded = 1`) are locked and
 * add-only — `setEnabled`/`delete` on one is rejected with an exception, and
 * the DAO's own `AND seeded = 0` guards hold even for a caller that bypasses
 * this class. Affected-row counting (rather than a read-then-write) keeps the
 * rejection race-free.
 *
 * v1 has no edit path: a user source changes by delete + re-add. The full engine
 * (tile scope, delivery, position) arrives later without touching this class's
 * contract.
 */
class SourceRepository(
    private val dao: SourceDao,
) {
    /** One `Flow<List<Source>>`: the read surface every consumer observes. */
    fun observe(): Flow<List<SourceEntity>> = dao.observeAll()

    /** Enabled rows of any kind — what the ingestor polls. */
    suspend fun enabledSources(): List<SourceEntity> = dao.enabled()

    /**
     * Enables or disables a user source.
     *
     * @throws IllegalStateException when the row is a locked seed or unknown.
     */
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (dao.updateEnabled(id, enabled, now) == 0) {
            throw IllegalStateException("source is locked or unknown: $id")
        }
    }

    /**
     * Deletes a user source.
     *
     * @throws IllegalStateException when the row is a locked seed or unknown.
     */
    suspend fun delete(id: String) {
        if (dao.deleteUserOnly(id) == 0) {
            throw IllegalStateException("source is locked or unknown: $id")
        }
    }

    /**
     * Inserts a user source after validating its spec.
     *
     * @throws IllegalArgumentException when the name is blank, the kind is
     * unknown, the spec is invalid for its kind, or the interval is outside
     * [MIN_INTERVAL_SEC]..[MAX_INTERVAL_SEC].
     */
    suspend fun addUserSource(
        name: String,
        kind: String,
        specJson: String,
        intervalSec: Long = DEFAULT_USER_INTERVAL_SEC,
        now: Long = System.currentTimeMillis(),
    ): SourceEntity {
        require(name.isNotBlank()) { "source name must not be blank" }
        require(intervalSec in MIN_INTERVAL_SEC..MAX_INTERVAL_SEC) {
            "interval must be $MIN_INTERVAL_SEC..$MAX_INTERVAL_SEC seconds"
        }
        SourceSpecs.parse(kind, specJson)
        val row =
            SourceEntity(
                id = Ulid.next(),
                name = name.trim(),
                kind = kind,
                specJson = specJson,
                seeded = false,
                enabled = true,
                createdAt = now,
                updatedAt = now,
                intervalSec = intervalSec,
            )
        dao.insertAll(listOf(row))
        return row
    }

    companion object {
        /**
         * A new feed re-polls on the same cadence the catalog feeds already
         * use — one owner for that number ([SyncScheduler.DEFAULT_INTERVAL_MINUTES]),
         * not a second arbitrary default.
         */
        const val DEFAULT_USER_INTERVAL_SEC: Long = SyncScheduler.DEFAULT_INTERVAL_MINUTES * 60L
        const val MIN_INTERVAL_SEC = 15L * 60
        const val MAX_INTERVAL_SEC = 24L * 60 * 60
    }
}
