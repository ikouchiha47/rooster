package com.personalos.app.ui.watchers

import com.personalos.app.core.Chars
import com.personalos.app.core.rules.MatchedClause
import com.personalos.app.data.RuleEntity
import com.personalos.app.data.RuleFire
import com.personalos.app.data.RuleWithFires
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watchers' decisions, tested without rendering: what the header counts, what a
 * rule's note says, which clauses are shown as the reason, and the order rules
 * are read in. The composable only lays these out.
 */
class WatchersModelTest {
    private fun rule(
        id: String,
        name: String = id,
    ) = RuleEntity(
        id = id,
        name = name,
        enabled = true,
        seeded = false,
        conditionJson = """{"source":"weather:bengaluru"}""",
        actionJson = """{"delivery":"none","position":0}""",
        position = 0L,
        createdAt = 0L,
    )

    private fun fire(
        ruleId: String,
        at: Long,
        clauses: List<String> = listOf("temp_c 40.2 > 40"),
    ) = RuleFire(
        ruleId = ruleId,
        itemId = "item-$at",
        matchedAt = at,
        title = "Bengaluru",
        source = "weather:bengaluru",
        itemTimestamp = at,
        clauses = clauses.map { MatchedClause(it) },
    )

    // ------------------------------------------------------------------ header

    @Test
    fun `the header counts rules and fires`() {
        assertEquals("0 RULES ${Chars.MIDDLE_DOT} 0 FIRES", watchersHeader(emptyList()))
        val groups =
            listOf(
                RuleWithFires(rule("a"), listOf(fire("a", 1), fire("a", 2))),
                RuleWithFires(rule("b"), emptyList()),
            )
        assertEquals("2 RULES ${Chars.MIDDLE_DOT} 2 FIRES", watchersHeader(groups))
    }

    // -------------------------------------------------------------------- note

    @Test
    fun `a rule that never fired says so rather than showing a zero`() {
        assertEquals("never fired", ruleNote(RuleWithFires(rule("a"), emptyList())))
        assertEquals("1", ruleNote(RuleWithFires(rule("a"), listOf(fire("a", 1)))))
    }

    // ----------------------------------------------------------------- reasons

    @Test
    fun `a fire shows its clauses as the reason, capped`() {
        val clauses = (1..5).map { "clause $it" }
        val shown = visibleClauses(fire("a", 1, clauses))
        assertEquals(listOf("clause 1", "clause 2", "clause 3"), shown)
    }

    @Test
    fun `a fire with no clauses shows nothing rather than a placeholder`() {
        assertTrue(visibleClauses(fire("a", 1, emptyList())).isEmpty())
    }

    // ------------------------------------------------------------------- order

    @Test
    fun `rules with the newest fire are read first`() {
        val groups =
            listOf(
                RuleWithFires(rule("old", "Old"), listOf(fire("old", 10))),
                RuleWithFires(rule("new", "New"), listOf(fire("new", 500))),
            )
        assertEquals(listOf("new", "old"), watchersOrder(groups).map { it.rule.id })
    }

    @Test
    fun `rules that never fired sit at the end, in name order`() {
        val groups =
            listOf(
                RuleWithFires(rule("z", "Zebra"), emptyList()),
                RuleWithFires(rule("fired", "Fired"), listOf(fire("fired", 5))),
                RuleWithFires(rule("a", "Apple"), emptyList()),
            )
        assertEquals(listOf("fired", "a", "z"), watchersOrder(groups).map { it.rule.id })
    }

    @Test
    fun `two rules fired at the same moment fall back to name`() {
        val groups =
            listOf(
                RuleWithFires(rule("b", "Beta"), listOf(fire("b", 7))),
                RuleWithFires(rule("a", "Alpha"), listOf(fire("a", 7))),
            )
        assertEquals(listOf("a", "b"), watchersOrder(groups).map { it.rule.id })
    }

    @Test
    fun `ordering does not invent or drop rules`() {
        val groups = listOf(RuleWithFires(rule("a"), emptyList()), RuleWithFires(rule("b"), emptyList()))
        val ordered = watchersOrder(groups)
        assertEquals(2, ordered.size)
        assertFalse(ordered.map { it.rule.id }.toSet() != setOf("a", "b"))
    }
}
