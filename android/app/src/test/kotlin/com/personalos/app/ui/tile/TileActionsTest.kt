package com.personalos.app.ui.tile

import com.personalos.app.core.tile.ActionKind
import com.personalos.app.ui.navigation.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileActionsTest {
    @Test
    fun `context actions open the screen that owns the fact`() {
        assertEquals(Destination.Rss, destinationForAction(ActionKind.ADD_FEED))
        assertEquals(Destination.Rules, destinationForAction(ActionKind.ADD_RULE))
        assertEquals(Destination.Settings, destinationForAction(ActionKind.SETTINGS))
        assertEquals(Destination.Settings, destinationForAction(ActionKind.ADD_PAIR))
    }

    @Test
    fun `sync and unknown actions do not navigate`() {
        assertNull(destinationForAction(ActionKind.SYNC))
        assertNull(destinationForAction(ActionKind.SEARCH))
        assertNull(destinationForAction(ActionKind.SCAN))
    }
}
