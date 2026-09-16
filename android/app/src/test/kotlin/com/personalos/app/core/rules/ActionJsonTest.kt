package com.personalos.app.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionJsonTest {
    @Test
    fun `push with a position parses`() {
        val action = ActionJson.parse("""{"delivery": "push", "position": 10}""")
        assertEquals(RuleAction(Delivery.PUSH, 10L), action)
    }

    @Test
    fun `delivery none with no position defaults to zero emphasis`() {
        assertEquals(RuleAction(Delivery.NONE, 0L), ActionJson.parse("""{"delivery": "none"}"""))
    }

    @Test
    fun `an explicit zero position parses`() {
        assertEquals(RuleAction(Delivery.NONE, 0L), ActionJson.parse("""{"delivery": "none", "position": 0}"""))
    }

    @Test
    fun `a misspelled key is rejected`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ActionJson.parse("""{"delivery": "push", "positon": 10}""")
            }
        assertTrue(error.message!!.contains("positon"))
    }

    @Test
    fun `an unknown delivery target is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionJson.parse("""{"delivery": "sms"}""")
        }
    }

    @Test
    fun `a missing delivery target is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionJson.parse("""{"position": 10}""")
        }
    }

    @Test
    fun `a negative position is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActionJson.parse("""{"delivery": "push", "position": -1}""")
        }
    }

    @Test
    fun `malformed json and non objects are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ActionJson.parse("{\"delivery\": \"push\"") }
        assertThrows(IllegalArgumentException::class.java) { ActionJson.parse("""["push"]""") }
    }
}
