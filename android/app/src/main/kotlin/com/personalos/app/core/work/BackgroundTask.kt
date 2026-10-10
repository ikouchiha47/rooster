package com.personalos.app.core.work

/** How far a background task has got, for the ongoing notification. */
data class TaskProgress(
    val done: Int,
    val total: Int,
    val label: String = "",
)

/**
 * A long-running job the app may run in the **foreground** so the OS does not defer
 * or cut it. The service (Android side) knows only this interface, so the *same*
 * service runs a re-tag, a download, a backfill — anything registered here.
 *
 * Android-free, so tasks are ordinary testable code; only the [BackgroundTaskRegistry]
 * and the service live near the platform.
 */
interface BackgroundTask {
    /** Stable id the starter asks for. */
    val id: String

    /** Shown in the notification while it runs. */
    val title: String

    /**
     * Runs to completion. [onProgress] updates the notification; returning false
     * marks the task as failed (the service still stops and clears it).
     */
    suspend fun run(onProgress: suspend (TaskProgress) -> Unit): Boolean
}

/** id → task, so the service resolves whichever task it was asked to run. */
class BackgroundTaskRegistry(
    private val tasks: List<BackgroundTask>,
) {
    fun get(id: String): BackgroundTask? = tasks.firstOrNull { it.id == id }

    val ids: List<String> get() = tasks.map { it.id }
}
