package com.personalos.workflow

import kotlin.test.Test
import kotlin.test.assertEquals

class NodeIdTest {
    @Test
    fun valueIsIdentity() {
        assertEquals(NodeId("a"), NodeId("a"))
        assertEquals("a", NodeId("a").toString())
    }
}
