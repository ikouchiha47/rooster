package com.personalos.app.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationCenterTest {
    private fun n(
        id: String,
        severity: Severity = Severity.INFO,
        at: Long = 0,
        body: String? = null,
    ) = Notification(id, NotificationKind.SYNC, severity, title = id, body = body, at = at)

    @Test
    fun `post adds, and posting the same id replaces rather than duplicates`() {
        val center = InMemoryNotificationCenter()
        center.post(n("a"))
        center.post(n("a", body = "second"))

        assertEquals(1, center.active.value.size)
        assertEquals(
            "second",
            center.active.value
                .single()
                .body,
        )
    }

    @Test
    fun `update changes only the named notification`() {
        val center = InMemoryNotificationCenter()
        center.post(n("a"))
        center.post(n("b"))
        center.update("a", "new")

        assertEquals(
            "new",
            center.active.value
                .first { it.id == "a" }
                .body,
        )
        assertNull(
            center.active.value
                .first { it.id == "b" }
                .body,
        )
    }

    @Test
    fun `dismiss removes only the named notification`() {
        val center = InMemoryNotificationCenter()
        center.post(n("a"))
        center.post(n("b"))
        center.dismiss("a")

        assertEquals(listOf("b"), center.active.value.map { it.id })
    }

    @Test
    fun `the loudest is shown, newest breaking a tie`() {
        assertNull(visibleNotification(emptyList()))
        // An error beats an info even when the info is newer.
        assertEquals(
            "err",
            visibleNotification(listOf(n("info", at = 10), n("err", severity = Severity.ERROR, at = 1)))!!.id,
        )
        // Same severity: the newest wins.
        assertEquals("new", visibleNotification(listOf(n("old", at = 1), n("new", at = 2)))!!.id)
    }
}
