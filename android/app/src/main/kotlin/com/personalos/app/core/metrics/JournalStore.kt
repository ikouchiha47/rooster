package com.personalos.app.core.metrics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.personalos.workflow.Journal
import com.personalos.workflow.JournalEvent
import com.personalos.workflow.JournalPhase
import com.personalos.workflow.NodeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The telemetry daemon's store: an **append-only** SQLite log, one table per day.
 *
 * Daily partitions exist so retention is a `DROP TABLE` rather than a `DELETE`
 * over a large table — dropping hands the pages back for reuse, and three days is
 * the whole budget, so older history is intentionally gone rather than pruned.
 *
 * Append-only is also what makes a crash legible: a `START` with no `END` is work
 * that never finished. No row is ever updated.
 *
 * Off unless enabled; when off, [record] is a no-op before it touches the disk.
 */
class JournalStore(
    context: Context,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Journal {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val knownTables = mutableSetOf<String>()

    private val helper =
        object : SQLiteOpenHelper(appContext, DATABASE, null, SCHEMA_VERSION) {
            override fun onConfigure(db: SQLiteDatabase) {
                // Append-only writes, so WAL (concurrent reads) and NORMAL sync are
                // the right trade: the log is disposable, not a ledger.
                db.enableWriteAheadLogging()
                db.execSQL("PRAGMA synchronous=NORMAL")
                db.execSQL("PRAGMA journal_size_limit=1048576")
            }

            override fun onCreate(db: SQLiteDatabase) = Unit

            /**
             * A schema change **starts clean**. Partition tables are created on
             * first use, so an older day table has older columns and
             * `CREATE TABLE IF NOT EXISTS` cannot fix it. Telemetry is disposable
             * by design (three days, then dropped), so dropping the partitions
             * costs at most three days of samples and keeps the writer simple.
             */
            override fun onUpgrade(
                db: SQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) {
                tables(db).forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
                knownTables.clear()
            }
        }

    /** Opt-in, read every write so a Settings toggle applies immediately. */
    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    override suspend fun record(event: JournalEvent) {
        if (!enabled) return
        withContext(Dispatchers.IO) {
            mutex.withLock { write(event) }
        }
    }

    /** The days present, newest first. */
    suspend fun days(): List<LocalDate> =
        withContext(Dispatchers.IO) {
            mutex.withLock { tables(helper.readableDatabase).mapNotNull(::dayOf).sortedDescending() }
        }

    /** One line per day: volume, failures, unfinished work, time and energy. */
    suspend fun summaries(): List<DaySummary> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = helper.readableDatabase
                tables(db)
                    .mapNotNull { table ->
                        val day = dayOf(table) ?: return@mapNotNull null
                        day to read(db, table)
                    }.sortedByDescending { it.first }
                    .map { (day, rows) -> summarize(day, rows) }
            }
        }

    /** Every row, oldest day first, as CSV for an external plot. */
    suspend fun csv(): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val db = helper.readableDatabase
                val days = tables(db).mapNotNull(::dayOf).sorted()
                buildString {
                    appendLine(
                        "day,at,host,run_id,node,attempt,phase,status,duration_ms,error,charge_uah,energy_nwh,elapsed_ms",
                    )
                    for (day in days) {
                        read(db, tableName(day)).forEach { e ->
                            append(day).append(',')
                            append(e.at).append(',')
                            append(e.host).append(',')
                            append(e.runId).append(',')
                            append(e.node).append(',')
                            append(e.attempt).append(',')
                            append(e.phase).append(',')
                            append(e.status?.name.orEmpty()).append(',')
                            append(e.durationMs ?: "").append(',')
                            append('"').append((e.error ?: "").replace("\"", "\"\"")).append('"').append(',')
                            append(e.chargeUah ?: "").append(',')
                            append(e.energyNwh ?: "").append(',')
                            append(e.elapsedMs ?: "")
                            appendLine()
                        }
                    }
                }
            }
        }

    // --------------------------------------------------------------------- write

    private fun write(event: JournalEvent) {
        val db = helper.writableDatabase
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(event.at), zone)
        val table = tableName(day)
        val created = ensureTable(db, table)
        db.insertOrThrow(table, null, event.toValues())
        // Once per day is enough: the day can only roll over while we are writing.
        if (created) prune(db, day)
    }

    /** True when the table did not exist yet. */
    private fun ensureTable(
        db: SQLiteDatabase,
        table: String,
    ): Boolean {
        if (table in knownTables) return false
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $table (
                at INTEGER NOT NULL,
                host TEXT NOT NULL,
                run_id TEXT NOT NULL,
                node TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                phase TEXT NOT NULL,
                status TEXT,
                duration_ms INTEGER,
                error TEXT,
                energy_nwh INTEGER,
                charge_uah INTEGER,
                elapsed_ms INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${table}_at ON $table (at)")
        knownTables += table
        return true
    }

    /** Drops every day older than [RETAIN_DAYS] — the retention is the window. */
    private fun prune(
        db: SQLiteDatabase,
        today: LocalDate,
    ) {
        val cutoff = today.minusDays((RETAIN_DAYS - 1).toLong())
        for (table in tables(db)) {
            val day = dayOf(table) ?: continue
            if (day.isBefore(cutoff)) {
                db.execSQL("DROP TABLE IF EXISTS $table")
                knownTables -= table
            }
        }
    }

    // ---------------------------------------------------------------------- read

    private fun tables(db: SQLiteDatabase): List<String> =
        db
            .rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '$TABLE_PREFIX%'",
                null,
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }

    private fun read(
        db: SQLiteDatabase,
        table: String,
    ): List<JournalEvent> =
        db.query(table, null, null, null, null, null, "at").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        JournalEvent(
                            at = cursor.getLong(cursor.getColumnIndexOrThrow("at")),
                            host = cursor.getString(cursor.getColumnIndexOrThrow("host")),
                            runId = cursor.getString(cursor.getColumnIndexOrThrow("run_id")),
                            node = cursor.getString(cursor.getColumnIndexOrThrow("node")),
                            attempt = cursor.getInt(cursor.getColumnIndexOrThrow("attempt")),
                            phase = JournalPhase.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("phase"))),
                            status =
                                cursor
                                    .getString(cursor.getColumnIndexOrThrow("status"))
                                    ?.let { NodeStatus.valueOf(it) },
                            durationMs = cursor.nullableLong("duration_ms"),
                            error = cursor.getString(cursor.getColumnIndexOrThrow("error")),
                            energyNwh = cursor.nullableLong("energy_nwh"),
                            chargeUah = cursor.nullableLong("charge_uah"),
                            elapsedMs = cursor.nullableLong("elapsed_ms"),
                        ),
                    )
                }
            }
        }

    private fun summarize(
        day: LocalDate,
        rows: List<JournalEvent>,
    ): DaySummary {
        val ends = rows.filter { it.phase == JournalPhase.END }
        val started = rows.filter { it.phase == JournalPhase.START }.map { it.key() }.toSet()
        val finished = ends.map { it.key() }.toSet()
        val energy = ends.mapNotNull { it.energyNwh }
        val charge = ends.mapNotNull { it.chargeUah }
        return DaySummary(
            day = day,
            events = rows.size,
            errors =
                ends.count {
                    it.status == NodeStatus.FAILED || it.status == NodeStatus.RETRY_EXHAUSTED
                },
            unfinished = started.count { it !in finished },
            workMs = ends.sumOf { it.durationMs ?: 0L },
            energyNwh = energy.takeIf { it.isNotEmpty() }?.sum(),
            chargeUah = charge.takeIf { it.isNotEmpty() }?.sum(),
        )
    }

    private companion object {
        const val DATABASE = "telemetry.db"
        const val SCHEMA_VERSION = 2
        const val TABLE_PREFIX = "events_"
        const val RETAIN_DAYS = 3
        const val PREFS = "telemetry"
        const val KEY_ENABLED = "enabled"
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }

    private fun tableName(day: LocalDate): String = TABLE_PREFIX + day.format(DAY_FORMAT)

    private fun dayOf(table: String): LocalDate? = runCatching { LocalDate.parse(table.removePrefix(TABLE_PREFIX), DAY_FORMAT) }.getOrNull()

    private fun JournalEvent.key(): Triple<String, String, Int> = Triple(runId, node, attempt)

    private fun JournalEvent.toValues(): ContentValues =
        ContentValues().apply {
            put("at", at)
            put("host", host)
            put("run_id", runId)
            put("node", node)
            put("attempt", attempt)
            put("phase", phase.name)
            status?.name?.let { put("status", it) } ?: putNull("status")
            if (durationMs != null) put("duration_ms", durationMs) else putNull("duration_ms")
            if (error != null) put("error", error) else putNull("error")
            if (energyNwh != null) put("energy_nwh", energyNwh) else putNull("energy_nwh")
            if (chargeUah != null) put("charge_uah", chargeUah) else putNull("charge_uah")
            if (elapsedMs != null) put("elapsed_ms", elapsedMs) else putNull("elapsed_ms")
        }

    private fun android.database.Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }
}

/** One day's line in the diagnostics view. */
data class DaySummary(
    val day: LocalDate,
    val events: Int,
    val errors: Int,
    val unfinished: Int,
    val workMs: Long,
    val energyNwh: Long?,
    val chargeUah: Long?,
)
