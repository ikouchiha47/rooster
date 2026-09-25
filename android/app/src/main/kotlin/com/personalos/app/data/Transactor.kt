package com.personalos.app.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction

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

/** The Room implementation: all-or-nothing, with the rollback Room provides. */
class RoomTransactor(
    private val database: RoomDatabase,
) : Transactor {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }
}
