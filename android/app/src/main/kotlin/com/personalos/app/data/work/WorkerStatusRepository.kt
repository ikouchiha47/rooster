package com.personalos.app.data.work

import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The single owner of background-job status. Watches each registered
 * unique-work name through WorkManager and maps every emission to plain
 * [JobSnapshot] facts; the UI decides order, labels and caps from those.
 *
 * One flow per name, combined: a new registered job is a new spec in the
 * watched list, never a new branch here. The data layer returns the full set
 * — one snapshot per watched name — and the component caps for display.
 */
class WorkerStatusRepository(
    private val workManager: WorkManager,
) {
    fun watch(specs: List<WorkerJobSpec>): Flow<List<JobSnapshot>> {
        if (specs.isEmpty()) return flowOf(emptyList())
        return combine(
            specs.map { spec -> infosFor(spec.uniqueName).map { infos -> summarizeJob(spec, infos) } },
        ) { snapshots -> snapshots.toList() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun infosFor(uniqueName: String): Flow<List<WorkInfo>> =
        callbackFlow {
            val live = workManager.getWorkInfosForUniqueWorkLiveData(uniqueName)
            val observer =
                Observer<List<WorkInfo>> { infos ->
                    trySend(infos.orEmpty())
                }
            live.observeForever(observer)
            awaitClose { live.removeObserver(observer) }
        }
}
