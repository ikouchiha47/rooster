package com.personalos.app.core.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundTaskRegistryTest {
    private class Task(
        override val id: String,
    ) : BackgroundTask {
        override val title: String = id

        override suspend fun run(onProgress: suspend (TaskProgress) -> Unit): Boolean = true
    }

    @Test
    fun `resolves a task by id`() {
        val registry = BackgroundTaskRegistry(listOf(Task("retag"), Task("download")))
        assertEquals("retag", registry.get("retag")?.id)
        assertEquals(listOf("retag", "download"), registry.ids)
    }

    @Test
    fun `an unknown id resolves to null`() {
        assertNull(BackgroundTaskRegistry(listOf(Task("retag"))).get("nope"))
    }
}
