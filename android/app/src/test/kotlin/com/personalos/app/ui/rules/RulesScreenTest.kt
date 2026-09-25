package com.personalos.app.ui.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesScreenTest {
    @Test
    fun `bundled rule opens in read only mode`() {
        assertTrue(isRuleReadOnly(seeded = true))
    }

    @Test
    fun `user rule opens in editable mode`() {
        assertFalse(isRuleReadOnly(seeded = false))
    }
}
