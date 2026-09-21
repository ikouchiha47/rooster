package com.personalos.app.data

import com.personalos.app.core.search.SmsSearch
import com.personalos.app.core.search.smsSearch
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The search SQL against a real SQLite carrying the real v19 DDL.
 *
 * Room skips compile-time verification of [MessageSearchDao]'s queries — there
 * is no entity for `events_fts` for it to check against — so without this test a
 * typo in `Sql.EVENTS_SEARCH_MATCH` (the join, the `rank` ordering, the
 * `ESCAPE '\'`) would only ever surface on a device. It also pins the two
 * behaviours the feature exists for: matching **inside** a word, and staying
 * scoped to SMS.
 */
class MessageSearchSqlTest {
    @Test
    fun `MATCH finds a sub-string inside a word`() {
        withSearchDatabase { db ->
            val smsId = insert(db, "sms", 10, "HDFCBK", "Rs 1214 debited to airtel 9876543210")
            assertEquals(listOf(smsId), ids(db, "rtel"))
            assertEquals(listOf(smsId), ids(db, "654"))
            assertEquals("a trailing partial word still hits", listOf(smsId), ids(db, "airte"))
        }
    }

    @Test
    fun `MATCH is case-insensitive`() {
        withSearchDatabase { db ->
            val smsId = insert(db, "sms", 10, "HDFCBK", "Rs 1214 debited to airtel 9876543210")
            assertEquals(listOf(smsId), ids(db, "AIRTEL"))
        }
    }

    @Test
    fun `multi-word queries are an AND, and stop at non-SMS rows`() {
        withSearchDatabase { db ->
            val smsId = insert(db, "sms", 10, "HDFCBK", "Rs 1214 debited to airtel 9876543210")
            // Same words, but a feed item: search must never surface it.
            insert(db, "rss:thehindu", 20, "Airtel 1214", "airtel and 1214 in a feed article")

            assertEquals(listOf(smsId), ids(db, "1214 airtel"))
            assertEquals("one term missing means no match", emptyList<Long>(), ids(db, "1214 zzzz"))
        }
    }

    @Test
    fun `the LIKE fallback still finds short queries and stays SMS-only`() {
        withSearchDatabase { db ->
            val smsId = insert(db, "sms", 10, "HDFCBK", "Rs 1214 debited to airtel ab")
            insert(db, "rss:thehindu", 20, "News", "ab across a feed article")

            // `ab` is below the trigram minimum, so it goes down the LIKE path.
            assertEquals(listOf(smsId), ids(db, "ab"))
        }
    }

    @Test
    fun `LIKE wildcards in the input are literal`() {
        withSearchDatabase { db ->
            val percent = insert(db, "sms", 10, "BANK", "discount 50% on bills")
            val underscore = insert(db, "sms", 20, "BANK", "rate is 9_9 today")
            insert(db, "sms", 30, "BANK", "discount 5000 on bills")
            insert(db, "sms", 40, "BANK", "rate is 919 today")

            // Both inputs are below the trigram minimum, so they take the LIKE
            // path; `%` and `_` must match themselves, not stand in for anything.
            assertEquals(listOf(percent), ids(db, "%"))
            assertEquals(listOf(underscore), ids(db, "9_"))
        }
    }

    @Test
    fun `the limit bounds the result set`() {
        withSearchDatabase { db ->
            insert(db, "sms", 10, "A", "airtel one")
            insert(db, "sms", 20, "B", "airtel two")
            assertEquals(1, ids(db, "airtel", limit = 1).size)
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun withSearchDatabase(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().executeUpdate(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    source TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL
                )
                """.trimIndent(),
            )
            // The real v19 statements, not a paraphrase of them.
            MIGRATION_18_19_STATEMENTS.forEach { db.createStatement().executeUpdate(it) }
            block(db)
        }
    }

    private fun insert(
        db: Connection,
        source: String,
        timestamp: Long,
        title: String,
        content: String,
    ): Long {
        db.prepareStatement("INSERT INTO events (source, timestamp, title, content) VALUES (?, ?, ?, ?)").use { st ->
            st.setString(1, source)
            st.setLong(2, timestamp)
            st.setString(3, title)
            st.setString(4, content)
            st.executeUpdate()
        }
        return db.createStatement().executeQuery("SELECT MAX(id) FROM events").use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    /** Runs the plan [smsSearch] builds through the real DAO SQL, returning ids. */
    private fun ids(
        db: Connection,
        raw: String,
        limit: Int = 50,
    ): List<Long> =
        when (val plan = smsSearch(raw)) {
            SmsSearch.Blank -> emptyList()
            is SmsSearch.Match -> run(db, Sql.EVENTS_SEARCH_MATCH, mapOf("match" to plan.expression, "limit" to limit))
            is SmsSearch.Like -> run(db, Sql.EVENTS_SEARCH_LIKE, mapOf("pattern" to plan.pattern, "limit" to limit))
        }

    /** Binds Room-style named parameters positionally, in order of appearance. */
    private fun run(
        db: Connection,
        sql: String,
        params: Map<String, Any>,
    ): List<Long> {
        val order = Regex(":(\\w+)").findAll(sql).map { it.groupValues[1] }.toList()
        val translated = sql.replace(Regex(":(\\w+)")) { "?" }
        return db.prepareStatement(translated).use { st ->
            order.forEachIndexed { index, name ->
                when (val value = params.getValue(name)) {
                    is String -> st.setString(index + 1, value)
                    is Int -> st.setInt(index + 1, value)
                    else -> error("unsupported bind: $value")
                }
            }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong("id")) } }
        }
    }
}
