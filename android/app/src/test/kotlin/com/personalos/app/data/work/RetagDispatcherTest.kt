package com.personalos.app.data.work

import com.personalos.app.core.tag.DeficitRoundRobin
import com.personalos.app.core.tag.FamilyState
import com.personalos.app.core.tag.RetagRun
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetagDispatcherTest {
    private class FakeStore(
        var deficits: Map<String, Int> = emptyMap(),
    ) : ScheduleStore {
        override suspend fun load(): Map<String, Int> = deficits

        override suspend fun save(deficits: Map<String, Int>) {
            this.deficits = deficits
        }
    }

    private fun dispatcher(
        store: ScheduleStore,
        budget: Int,
        run: suspend (String) -> RetagRun,
    ) = RetagDispatcher(
        families = listOf(FamilyState("sms", 4), FamilyState("feed", 1)),
        policy = DeficitRoundRobin(),
        store = store,
        runFamily = run,
        budget = budget,
    )

    @Test
    fun `a tick runs chunks in weighted order`() =
        runBlocking {
            val order = mutableListOf<String>()
            dispatcher(FakeStore(), budget = 5) { family ->
                order += family
                RetagRun(tagged = 1, exhausted = false)
            }.tick()

            assertEquals(listOf("sms", "sms", "sms", "sms", "feed"), order)
        }

    @Test
    fun `an exhausted family stops being scheduled`() =
        runBlocking {
            val order = mutableListOf<String>()
            dispatcher(FakeStore(), budget = 8) { family ->
                order += family
                RetagRun(tagged = 0, exhausted = family == "feed")
            }.tick()

            assertEquals("feed runs once, then is skipped", 1, order.count { it == "feed" })
        }

    @Test
    fun `deficits are persisted for the next tick`() =
        runBlocking {
            val store = FakeStore()
            dispatcher(store, budget = 3) { RetagRun(tagged = 1, exhausted = false) }.tick()

            assertTrue("both families' deficits are saved", setOf("sms", "feed").all { it in store.deficits })
        }

    @Test
    fun `it reports whether work remains`() =
        runBlocking {
            val more = dispatcher(FakeStore(), budget = 2) { RetagRun(tagged = 1, exhausted = false) }.tick()
            val done = dispatcher(FakeStore(), budget = 2) { RetagRun(tagged = 0, exhausted = true) }.tick()

            assertTrue("not exhausted yet", more)
            assertEquals(false, done)
        }
}
