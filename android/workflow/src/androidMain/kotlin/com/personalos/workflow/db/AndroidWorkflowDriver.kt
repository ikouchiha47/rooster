package com.personalos.workflow.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Builds a durable [SqlDriver] for Android, backed by `androidx.sqlite` — the same
 * SQLite layer Room uses, so the app ships one SQLite runtime and two databases.
 * The host owns the driver's lifecycle and its file name.
 */
fun androidWorkflowDriver(
    context: Context,
    name: String = "workflow.db",
): SqlDriver = AndroidSqliteDriver(WorkflowDatabase.Schema, context, name)
