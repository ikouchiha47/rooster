package com.personalos.app.data

import androidx.room.RoomDatabase
import androidx.room.useWriterConnection

/**
 * Runs a set of writes as one unit.
 *
 * A single "observation" is more than one row — the `events` row and the
 * `item_fields` that belong to it — and a reader must never catch it half
 * written. Without this, the two writes are separate statements and a read
 * between them sees a sample with the new timestamp and **no fields at all**,
 * or the previous sample's fields, with nothing to re-notify it afterwards
 * (`item_fields` carries no timestamp of its own).
 *
 * A one-method interface rather than passing the database around, so the
 * writers stay testable without Room: a fake transactor simply runs the block.
 */
interface Transactor {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/**
 * The Room implementation: all-or-nothing, with the rollback Room provides.
 *
 * It goes through [useWriterConnection] on purpose. `RoomDatabase.withTransaction`
 * is the *platform* transaction: it ends at `beginTransaction()`, which asks for
 * a `SupportSQLiteOpenHelper` — the very thing a database opened with a driver
 * (this app uses `BundledSQLiteDriver` for FTS5) does not have. On the device
 * that surfaced as weather and fx never writing an observation, each run
 * failing with "Cannot return a SupportSQLiteOpenHelper…". The driver's own
 * `Transactor` is the transaction API that exists in driver mode.
 */
class RoomTransactor(
    private val database: RoomDatabase,
) : Transactor {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.useWriterConnection { writer ->
            // IMMEDIATE, like Room's own write transaction: take the write lock
            // up front rather than upgrading mid-transaction.
            writer.withTransaction(androidx.room.Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
        }
}
