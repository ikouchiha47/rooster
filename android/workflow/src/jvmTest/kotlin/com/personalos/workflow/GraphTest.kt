package com.personalos.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GraphTest {
    @Test
    fun topologicalOrderRespectsEdges() {
        val graph =
            workflow("g") {
                val a = task("a", "step")
                val b = task("b", "step")
                val c = task("c", "step")
                edge(a, b)
                edge(a, c)
                edge(b, c)
            }.graph

        assertEquals(listOf("a", "b", "c"), graph.topologicalOrder().map { it.value })
    }

    @Test
    fun levelsGroupIndependentNodes() {
        val graph =
            workflow("g") {
                val a = task("a", "step")
                val b = task("b", "step")
                val c = task("c", "step")
                val d = task("d", "step")
                edge(a, b)
                edge(a, c)
                edge(b, d)
                edge(c, d)
            }.graph

        val levels = graph.levels()
        assertEquals(0, levels[NodeId("a")])
        assertEquals(1, levels[NodeId("b")])
        assertEquals(1, levels[NodeId("c")])
        assertEquals(2, levels[NodeId("d")])
    }

    @Test
    fun cycleIsRejected() {
        assertFailsWith<WorkflowGraphException> {
            workflow("g") {
                val a = task("a", "step")
                val b = task("b", "step")
                edge(a, b)
                edge(b, a)
            }
        }
    }

    @Test
    fun branchNeedsBothEdges() {
        assertFailsWith<IllegalArgumentException> {
            workflow("g") {
                val p = branch("p", "when")
                val t = task("t", "step")
                edge(p, t, Branch.THEN)
            }
        }
    }

    @Test
    fun forkNeedsTwoBranches() {
        assertFailsWith<IllegalArgumentException> {
            workflow("g") {
                val f = fork("f")
                val a = task("a", "step")
                edge(f, a)
            }
        }
    }

    @Test
    fun joinNeedsTwoBranches() {
        assertFailsWith<IllegalArgumentException> {
            workflow("g") {
                val a = task("a", "step")
                val j = join("j")
                edge(a, j)
            }
        }
    }
}
