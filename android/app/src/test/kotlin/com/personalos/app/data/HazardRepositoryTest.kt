package com.personalos.app.data

import com.personalos.app.core.hazard.HazardWindow
import com.personalos.app.core.hazard.Quake
import com.personalos.app.core.hazard.QuakeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardRepositoryTest {
    @Test
    fun `failure keeps the last good snapshot`() =
        runBlocking {
            val source = FakeSource()
            val repository = HazardRepository(source, now = { 1_000L })

            repository.refresh()
            assertEquals(
                listOf("q1"),
                repository.snapshot.value.quakes
                    .map { it.id },
            )
            assertEquals(1_000L, repository.snapshot.value.fetchedAt)

            source.failure = IllegalStateException("offline")
            repository.refresh()

            assertEquals(
                listOf("q1"),
                repository.snapshot.value.quakes
                    .map { it.id },
            )
            assertEquals(1_000L, repository.snapshot.value.fetchedAt)
            assertEquals("offline", repository.snapshot.value.error)
        }

    @Test
    fun `failure with no previous success stays empty`() =
        runBlocking {
            val repository =
                HazardRepository(
                    FakeSource(failure = IllegalStateException("offline")),
                    now = { 2_000L },
                )

            repository.refresh()

            assertTrue(
                repository.snapshot.value.quakes
                    .isEmpty(),
            )
            assertNull(repository.snapshot.value.fetchedAt)
            assertEquals("offline", repository.snapshot.value.error)
        }

    private class FakeSource(
        var failure: Exception? = null,
    ) : QuakeSource {
        override val id: String = "fake"

        override suspend fun fetch(window: HazardWindow): List<Quake> {
            failure?.let { throw it }
            return listOf(
                Quake(
                    id = "q1",
                    magnitude = 3.2,
                    place = "Somewhere",
                    time = 1L,
                    lat = 1.0,
                    lon = 2.0,
                    depth = 3.0,
                    url = "https://example.test/q1",
                ),
            )
        }
    }
}
