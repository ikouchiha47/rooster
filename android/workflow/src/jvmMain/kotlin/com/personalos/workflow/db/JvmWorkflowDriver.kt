package com.personalos.workflow.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

/**
 * Builds a durable [SqlDriver] for the JVM (and, later, anything that is not Android
 * or native). WAL and a busy timeout are set explicitly: the engine reads while a step
 * writes, and SQLite's default rollback journal plus no timeout fails those reads fast
 * instead of waiting — the failure the KMPWorker spike found it never configured.
 */
fun jvmWorkflowDriver(
    path: String = "workflow.db",
    busyTimeoutMillis: Int = 5_000,
): SqlDriver {
    val driver = JdbcSqliteDriver("jdbc:sqlite:$path", Properties(), WorkflowDatabase.Schema)
    driver.execute(null, "PRAGMA journal_mode=WAL;", 0)
    driver.execute(null, "PRAGMA busy_timeout=$busyTimeoutMillis;", 0)
    return driver
}
