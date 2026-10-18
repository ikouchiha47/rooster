package com.personalos.app.data.work

import com.personalos.app.core.resilience.InMemoryCircuitStore
import com.personalos.app.core.tag.DeficitRoundRobin
import com.personalos.app.core.tag.FamilyState
import com.personalos.app.core.tag.RetagRun
import com.personalos.app.core.work.Failure
import com.personalos.app.core.work.RetryPolicy
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
    fun `a persistently failing family ends the run instead of spinning`() =
        runBlocking {
            // A step that throws every time must NOT be reported as "more work" — that
            // is what made the foreground service loop (10k ticks, no progress).
            val dispatcher =
                RetagDispatcher(
                    families = listOf(FamilyState("sms", 1)),
                    policy = DeficitRoundRobin(),
                    store = FakeStore(),
                    runFamily = { throw IllegalStateException("boom") },
                    budget = 4,
                )

            assertEquals("a failed family is not 'more work'", false, dispatcher.tick())
        }

    @Test
    fun `a transient failure is retried within the run`() =
        runBlocking {
            var calls = 0
            val dispatcher =
                RetagDispatcher(
                    families = listOf(FamilyState("sms", 1)),
                    policy = DeficitRoundRobin(),
                    store = FakeStore(),
                    runFamily = {
                        calls++
                        if (calls == 1) throw java.io.IOException("blip") else RetagRun(tagged = 1, exhausted = false)
                    },
                    budget = 1,
                    retry = RetryPolicy(baseDelayMs = 0),
                    health = InMemoryCircuitStore(),
                    now = { 0 },
                )

            val more = dispatcher.tick()

            assertEquals("retried then succeeded", 2, calls)
            assertEquals("succeeded family still has work", true, more)
        }

    @Test
    fun `a permanent failure is not retried and ends the run`() =
        runBlocking {
            var calls = 0
            val dispatcher =
                RetagDispatcher(
                    families = listOf(FamilyState("sms", 1)),
                    policy = DeficitRoundRobin(),
                    store = FakeStore(),
                    runFamily = {
                        calls++
                        throw IllegalStateException("bug")
                    },
                    budget = 4,
                    retry = RetryPolicy(baseDelayMs = 0),
                    health = InMemoryCircuitStore(),
                    now = { 0 },
                )

            assertEquals("not 'more work'", false, dispatcher.tick())
            assertEquals("permanent is not retried", 1, calls)
        }

    @Test
    fun `a paused family is skipped and is not more work`() =
        runBlocking {
            val health = InMemoryCircuitStore()
            health.onFailure("sms", Failure.PERMANENT, now = 0)
            var called = false
            val dispatcher =
                RetagDispatcher(
                    families = listOf(FamilyState("sms", 1)),
                    policy = DeficitRoundRobin(),
                    store = FakeStore(),
                    runFamily = {
                        called = true
                        RetagRun(tagged = 1, exhausted = false)
                    },
                    budget = 4,
                    health = health,
                    now = { 1 },
                )

            assertEquals("paused → run ends", false, dispatcher.tick())
            assertEquals("a paused family is not run", false, called)
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
