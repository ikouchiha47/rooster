package com.personalos.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The cross-rule Alerts query against a real SQLite, with the real SQL.
 *
 * The join, the `GROUP_CONCAT` fold and the `(timestamp, event id)` keyset are
 * exactly what Room cannot validate for us: a wrong grouping, a shared-rule id
 * leaking across an event, or a cursor that skips a same-timestamp event would
 * only ever surface on a device. This runs [Sql.ITEM_RULES_ALERTS_PAGE] and
 * [Sql.ITEM_RULES_ALERTS_COUNT] verbatim, the way `ConversationTriggerTest` runs
 * the trigger DDL.
 */
class RuleAlertsQueryTest {
    @Test
    fun `one row per event, with every rule folded in, newest event first`() {
        withAlertsDatabase { db ->
            event(db, "ulid-1", timestamp = 100)
            event(db, "ulid-2", timestamp = 200)
            match(db, "ulid-1", "hot", matchedAt = 500)
            match(db, "ulid-1", "cold", matchedAt = 501)
            match(db, "ulid-2", "hot", matchedAt = 600)

            val rows = alerts(db, cursorTs = null, cursorId = 0L, limit = 10)
            assertEquals(listOf("ulid-2", "ulid-1"), rows.map { it.itemId })
            assertEquals(listOf("hot"), rows[0].ruleIds)
            // The fold's order is not part of the query's contract; the repository
            // imposes name order. The query only has to carry both rules.
            assertEquals(setOf("hot", "cold"), rows[1].ruleIds.toSet())
        }
    }

    @Test
    fun `the keyset walks by (timestamp, event id) without skipping or duplicating`() {
        withAlertsDatabase { db ->
            // Two events share a timestamp, so only the event id can break the tie.
            event(db, "ulid-1", timestamp = 100)
            event(db, "ulid-2", timestamp = 100)
            event(db, "ulid-3", timestamp = 50)
            match(db, "ulid-1", "hot", 1)
            match(db, "ulid-2", "hot", 1)
            match(db, "ulid-3", "hot", 1)

            val pageOne = alerts(db, cursorTs = null, cursorId = 0L, limit = 2)
            assertEquals("newest first, event id breaks the tie", listOf("ulid-2", "ulid-1"), pageOne.map { it.itemId })

            val pageTwo = alerts(db, pageOne.last().timestamp, pageOne.last().eventId, limit = 2)
            assertEquals(listOf("ulid-3"), pageTwo.map { it.itemId })

            // The two pages cover every event exactly once.
            assertEquals(
                setOf("ulid-1", "ulid-2", "ulid-3"),
                (pageOne + pageTwo).map { it.itemId }.toSet(),
            )
        }
    }

    @Test
    fun `the badge counts distinct events, not fires`() {
        withAlertsDatabase { db ->
            event(db, "ulid-1", timestamp = 100)
            event(db, "ulid-2", timestamp = 200)
            match(db, "ulid-1", "hot", 1)
            match(db, "ulid-1", "cold", 1)
            match(db, "ulid-2", "hot", 1)

            db.createStatement().executeQuery(Sql.ITEM_RULES_ALERTS_COUNT).use { rs ->
                rs.next()
                assertEquals("two events, three fires", 2, rs.getInt(1))
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private data class AlertRow(
        val itemId: String,
        val eventId: Long,
        val timestamp: Long,
        val ruleIds: List<String>,
    )

    /**
     * Runs the real [Sql.ITEM_RULES_ALERTS_PAGE]. SQLite's JDBC driver has no
     * named parameters, so each `:name` becomes a `?` and is bound in order,
     * repeating the value for a name that appears more than once — a test of the
     * query the app actually runs, not a paraphrase.
     */
    private fun alerts(
        db: Connection,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<AlertRow> {
        val params = mapOf<String, Any?>("cursorTs" to cursorTs, "cursorId" to cursorId, "limit" to limit)
        val order = Regex(":(\\w+)").findAll(Sql.ITEM_RULES_ALERTS_PAGE).map { it.groupValues[1] }.toList()
        val sql = Regex(":(\\w+)").replace(Sql.ITEM_RULES_ALERTS_PAGE) { "?" }
        return db.prepareStatement(sql).use { st ->
            order.forEachIndexed { i, name ->
                val value = params[name]
                if (value == null) st.setNull(i + 1, java.sql.Types.NULL) else st.setObject(i + 1, value)
            }
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AlertRow(
                                itemId = rs.getString("item_id"),
                                eventId = rs.getLong("event_id"),
                                timestamp = rs.getLong("item_timestamp"),
                                ruleIds = rs.getString("rule_ids").split(','),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun withAlertsDatabase(block: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().executeUpdate(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    ulid TEXT NOT NULL,
                    source TEXT NOT NULL,
                    type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    content TEXT
                )
                """.trimIndent(),
            )
            db.createStatement().executeUpdate(
                """
                CREATE TABLE item_rules (
                    item_id TEXT NOT NULL,
                    rule_id TEXT NOT NULL,
                    matched_at INTEGER NOT NULL,
                    PRIMARY KEY (item_id, rule_id)
                )
                """.trimIndent(),
            )
            block(db)
        }
    }

    private fun event(
        db: Connection,
        ulid: String,
        timestamp: Long,
    ): Long {
        db
            .prepareStatement(
                "INSERT INTO events (ulid, source, type, title, timestamp, content) VALUES (?, 'sms', 'inbox', ?, ?, '')",
            ).use { st ->
                st.setString(1, ulid)
                st.setString(2, ulid)
                st.setLong(3, timestamp)
                st.executeUpdate()
            }
        return db.createStatement().executeQuery("SELECT MAX(id) FROM events").use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    private fun match(
        db: Connection,
        itemId: String,
        ruleId: String,
        matchedAt: Long,
    ) {
        db
            .prepareStatement("INSERT INTO item_rules (item_id, rule_id, matched_at) VALUES (?, ?, ?)")
            .use { st ->
                st.setString(1, itemId)
                st.setString(2, ruleId)
                st.setLong(3, matchedAt)
                st.executeUpdate()
            }
    }
}
