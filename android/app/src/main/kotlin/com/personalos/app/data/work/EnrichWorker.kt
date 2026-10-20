package com.personalos.app.data.work

import android.content.Context
import androidx.work.WorkerParameters

/**
 * Scheduled enrichment, as a thin [DrainWorker] host.
 *
 * The shared host owns progress, chaining while candidates remain, and the
 * bounded retry/failure mapping. This class only binds the enrichment spec.
 */
class EnrichWorker(
    appContext: Context,
    params: WorkerParameters,
) : DrainWorker(appContext, params) {
    override fun specFor(key: String): DrainSpec = Drains.ENRICHMENT

    companion object {
        const val KEY_ENRICHED = "enriched"
    }
}
