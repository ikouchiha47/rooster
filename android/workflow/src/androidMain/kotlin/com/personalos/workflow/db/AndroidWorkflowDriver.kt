package com.personalos.workflow.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Builds a durable [SqlDriver] for Android, backed by `androidx.sqlite` — the same
 * SQLite layer Room uses, so the app ships one SQLite runtime and two databases.
 * The host owns the driver's lifecycle and its file name.
 *
 * **WAL on purpose.** The ledger is a durable record written by background workers
 * while the Diagnostics screen may read it, and WAL lets the two overlap instead
 * of serialising behind a single writer lock. `synchronous=NORMAL` is the matching
 * trade: with WAL a lost commit costs the last transaction, and this database can
 * always replay the work it lost — it is a run log, not a bank.
 */
fun androidWorkflowDriver(
    context: Context,
    name: String = "workflow.db",
): SqlDriver =
    AndroidSqliteDriver(
        schema = WorkflowDatabase.Schema,
        context = context,
        name = name,
        callback =
            object : AndroidSqliteDriver.Callback(WorkflowDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    // `journal_mode` returns the resulting mode as a row, and
                    // execSQL rejects statements that produce rows.
                    db.query("PRAGMA journal_mode=WAL").use { it.moveToFirst() }
                    db.execSQL("PRAGMA synchronous=NORMAL")
                }
            },
    )
