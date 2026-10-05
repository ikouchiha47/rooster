package com.personalos.app.core.sync

import com.personalos.app.core.notify.InMemoryNotificationCenter
import com.personalos.app.core.notify.NotificationKind
import com.personalos.app.core.notify.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync is a *producer*: it posts one notification while running, updates its
 * step, and dismisses it after — or leaves an error behind when it fails. Pinned
 * here so the notification contract is a rule, not an implementation detail.
 */
class SyncStatusTest {
    @Test
    fun `a run posts a sync notification, updates the step, and dismisses it`() =
        runBlocking {
            val center = InMemoryNotificationCenter()
            val sync = SyncStatus(center)

            var during: List<com.personalos.app.core.notify.Notification> = emptyList()
            sync.run("test") {
                during = center.active.value
                sync.step("halfway")
                assertEquals(
                    "halfway",
                    center.active.value
                        .single()
                        .body,
                )
            }

            assertEquals(NotificationKind.SYNC, during.single().kind)
            assertTrue("dismissed afterwards", center.active.value.isEmpty())
        }

    @Test
    fun `a failed run leaves an error notification with a ttl`() =
        runBlocking {
            val center = InMemoryNotificationCenter()
            val sync = SyncStatus(center)

            sync.run("test") { error("boom") }

            val error = center.active.value.single()
            assertEquals(Severity.ERROR, error.severity)
            assertEquals("boom", error.body)
            assertTrue("an error clears itself", error.ttlMs != null)
        }
}
