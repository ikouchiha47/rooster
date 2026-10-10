package com.personalos.app.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetagSchedulingTest {
    private val policy = DeficitRoundRobin()

    private fun s(
        family: String,
        weight: Int,
        deficit: Int = 0,
        exhausted: Boolean = false,
    ) = FamilyState(family, weight, deficit, exhausted)

    @Test
    fun `pick is the highest deficit`() {
        assertEquals("b", policy.pick(listOf(s("a", 1, 1), s("b", 1, 2))))
    }

    @Test
    fun `ties break by weight, then name`() {
        assertEquals("heavy", policy.pick(listOf(s("light", 1, 2), s("heavy", 4, 2))))
        assertEquals("z", policy.pick(listOf(s("a", 1, 2), s("z", 1, 2))))
    }

    @Test
    fun `exhausted families are never picked`() {
        assertNull(policy.pick(listOf(s("a", 9, 9, exhausted = true), s("b", 1, 0))))
    }

    @Test
    fun `credit is earned by non-exhausted families only`() {
        val out = policy.credit(listOf(s("a", 2), s("b", 5, exhausted = true)))
        assertEquals(2, out.first { it.family == "a" }.deficit)
        assertEquals(0, out.first { it.family == "b" }.deficit)
    }

    @Test
    fun `charge debits only the chosen family`() {
        val out = policy.charge(listOf(s("a", 1, 3), s("b", 1, 3)), "a")
        assertEquals(2, out.first { it.family == "a" }.deficit)
        assertEquals(3, out.first { it.family == "b" }.deficit)
    }

    @Test
    fun `a weight-4 family gets four chunks a round, a weight-1 one gets one`() {
        var states = policy.credit(listOf(s("sms", 4), s("feed", 1)))
        val picks = mutableListOf<String>()
        while (true) {
            val family = policy.pick(states) ?: break
            picks += family
            states = policy.charge(states, family)
        }
        assertEquals(listOf("sms", "sms", "sms", "sms", "feed"), picks)
    }

    @Test
    fun `the low-weight family runs every round — no starvation`() {
        var states = listOf(s("sms", 9), s("feed", 1))
        repeat(5) {
            states = policy.credit(states)
            val picks = mutableListOf<String>()
            while (true) {
                val family = policy.pick(states) ?: break
                picks += family
                states = policy.charge(states, family)
            }
            assertTrue("feed must run each round", "feed" in picks)
        }
    }
}
