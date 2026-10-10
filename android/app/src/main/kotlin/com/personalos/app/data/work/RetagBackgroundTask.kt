package com.personalos.app.data.work

import com.personalos.app.core.work.BackgroundTask
import com.personalos.app.core.work.TaskProgress

/**
 * The re-tag, as a [BackgroundTask] the foreground service can run to completion.
 * Loops the dispatcher until every family is exhausted; the service's timeout keeps
 * it within the OS budget, and the cursors mean a stopped run resumes.
 */
class RetagBackgroundTask(
    private val dispatcher: RetagDispatcher,
) : BackgroundTask {
    override val id: String = "retag"

    override val title: String = "Tagging messages"

    override suspend fun run(onProgress: suspend (TaskProgress) -> Unit): Boolean {
        var ticks = 0
        while (ticks < MAX_TICKS) {
            val more = dispatcher.tick()
            ticks++
            onProgress(TaskProgress(done = ticks, total = 0, label = "pass"))
            if (!more) return true
        }
        return true
    }

    private companion object {
        /** Safety bound; the timeout/cursors are the real limiters. */
        const val MAX_TICKS = 10_000
    }
}
