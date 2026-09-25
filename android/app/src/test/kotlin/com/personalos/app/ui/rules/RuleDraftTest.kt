package com.personalos.app.ui.rules

import com.personalos.app.core.mention.MentionKind
import com.personalos.app.core.rules.FieldNames
import com.personalos.app.core.rules.FieldOp
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.rules.TextTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDraftTest {
    @Test
    fun `empty draft is invalid`() {
        val draft = RuleDraft.empty()
        assertFalse(draft.isValid())
    }

    @Test
    fun `draft with name and one predicate is valid`() {
        val draft =
            RuleDraft(
                name = "Test",
                composition = RuleDraft.Composition.ALL,
                predicates = listOf(PredicateDraft.Subject("finance")),
                push = true,
                position = 10L,
            )
        assertTrue(draft.isValid())
    }

    @Test
    fun `blank name is invalid`() {
        val draft =
            RuleDraft(
                name = "  ",
                composition = RuleDraft.Composition.ALL,
                predicates = listOf(PredicateDraft.Subject("finance")),
                push = true,
                position = 0L,
            )
        assertFalse(draft.isValid())
    }

    @Test
    fun `emits all composition json`() {
        val draft =
            RuleDraft(
                name = "A",
                composition = RuleDraft.Composition.ALL,
                predicates = listOf(PredicateDraft.Subject("finance")),
                push = true,
                position = 0L,
            )
        assertEquals("""{"all":[{"subject":"finance"}]}""", draft.toConditionJson())
    }

    @Test
    fun `emits any composition json`() {
        val draft =
            RuleDraft(
                name = "A",
                composition = RuleDraft.Composition.ANY,
                predicates = listOf(PredicateDraft.Subject("finance"), PredicateDraft.Nature("incident")),
                push = false,
                position = 0L,
            )
        assertEquals("""{"any":[{"subject":"finance"},{"nature":"incident"}]}""", draft.toConditionJson())
    }

    @Test
    fun `emits action json with push`() {
        val draft = RuleDraft.empty().copy(push = true, position = 10L)
        assertEquals("""{"delivery":"push","position":10}""", draft.toActionJson())
    }

    @Test
    fun `emits action json with none`() {
        val draft = RuleDraft.empty().copy(push = false, position = 0L)
        assertEquals("""{"delivery":"none","position":0}""", draft.toActionJson())
    }

    @Test
    fun `field predicate emits typed number`() {
        val draft =
            RuleDraft(
                name = "A",
                composition = RuleDraft.Composition.ALL,
                predicates = listOf(PredicateDraft.Field("amount", FieldOp.GT, FieldValue.Num(10000.0))),
                push = true,
                position = 0L,
            )
        assertEquals("""{"all":[{"field":{"name":"amount","op":"gt","value":10000.0}}]}""", draft.toConditionJson())
    }

    @Test
    fun `text predicate emits json`() {
        val draft =
            RuleDraft(
                name = "A",
                composition = RuleDraft.Composition.ALL,
                predicates = listOf(PredicateDraft.Text("bandh", TextTarget.ANY)),
                push = true,
                position = 0L,
            )
        assertEquals(
            """{"all":[{"text":{"pattern":"bandh","target":"any"}}]}""",
            draft.toConditionJson(),
        )
    }

    @Test
    fun `roundtrip from stored rule`() {
        val draft =
            RuleDraft.fromRule(
                name = "Large SMS amount watch",
                conditionJson = """{"all":[{"source":"sms"},{"field":{"name":"amount","op":"gt","value":10000}}]}""",
                actionJson = """{"delivery":"push","position":50}""",
            )
        assertEquals("Large SMS amount watch", draft.name)
        assertEquals(RuleDraft.Composition.ALL, draft.composition)
        assertEquals(2, draft.predicates.size)
        assertTrue(draft.push)
        assertEquals(50L, draft.position)
        assertTrue(draft.isValid())
    }

    @Test
    fun `roundtrip preserves any composition`() {
        val draft =
            RuleDraft.fromRule(
                name = "X",
                conditionJson = """{"any":[{"subject":"finance"},{"nature":"incident"}]}""",
                actionJson = """{"delivery":"none","position":0}""",
            )
        assertEquals(RuleDraft.Composition.ANY, draft.composition)
        assertEquals(2, draft.predicates.size)
        assertFalse(draft.push)
    }

    @Test
    fun `predicate summary formats correctly`() {
        assertEquals("subject is finance", PredicateDraft.Subject("finance").summary())
        assertEquals("nature is incident", PredicateDraft.Nature("incident").summary())
        assertEquals("marker is news", PredicateDraft.Marker.summary())
        assertEquals("place is Kolkata", PredicateDraft.Mention("place", "Kolkata").summary())
        assertEquals("source is sms", PredicateDraft.Source("sms").summary())
        assertEquals("amount > 10000", PredicateDraft.Field("amount", FieldOp.GT, FieldValue.Num(10000.0)).summary())
        assertEquals("""text(any) ~ "bandh"""", PredicateDraft.Text("bandh", TextTarget.ANY).summary())
    }

    @Test
    fun `field predicate is invalid when name is outside supplied set`() {
        val valid = PredicateDraft.Field("amount", FieldOp.GT, FieldValue.Num(100.0))
        val invalid = PredicateDraft.Field("unknown", FieldOp.EQ, FieldValue.Str("x"))
        assertTrue(valid.isValid())
        assertFalse(invalid.isValid())
    }

    @Test
    fun `mention predicate is invalid when kind is outside mention kind constants`() {
        val valid = PredicateDraft.Mention(MentionKind.PLACE, "Kolkata")
        val invalid = PredicateDraft.Mention("unknown", "Kolkata")
        assertTrue(valid.isValid())
        assertFalse(invalid.isValid())
    }

    @Test
    fun `restricted field predicate still emits parsable json`() {
        val draft =
            RuleDraft(
                name = "A",
                composition = RuleDraft.Composition.ALL,
                predicates = listOf(PredicateDraft.Field("sender", FieldOp.CONTAINS, FieldValue.Str("bank"))),
                push = true,
                position = 0L,
            )
        val json = draft.toConditionJson()
        com.personalos.app.core.rules.ConditionJson
            .parse(json)
    }

    @Test
    fun `restricted mention predicate still emits parsable json`() {
        val draft =
            RuleDraft(
                name = "A",
                composition = RuleDraft.Composition.ALL,
                predicates = listOf(PredicateDraft.Mention(MentionKind.PLACE, "Kolkata")),
                push = true,
                position = 0L,
            )
        val json = draft.toConditionJson()
        com.personalos.app.core.rules.ConditionJson
            .parse(json)
    }

    @Test
    fun `restricted source predicate still emits parsable json`() {
        val draft =
            RuleDraft(
                name = "A",
                composition = RuleDraft.Composition.ALL,
                predicates = listOf(PredicateDraft.Source("sms")),
                push = true,
                position = 0L,
            )
        val json = draft.toConditionJson()
        com.personalos.app.core.rules.ConditionJson
            .parse(json)
    }

    @Test
    fun `field names supplied is the exact closed set`() {
        // Guard against a future change that accidentally widens or narrows
        // the set without updating the UI dropdown.
        assertEquals(setOf("sender", "amount"), FieldNames.SUPPLIED)
    }
}
