package com.personalos.app.data.work

import android.content.Context
import androidx.work.WorkerParameters

/**
 * The fast re-tag drain, as a thin [DrainWorker] host.
 *
 * It is the **single processor** for retagging (ADR 0010 §5): the periodic recon
 * only nudges this unique job, and the host chains another batch while the
 * derived backlog remains.
 */
class RetagDispatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : DrainWorker(appContext, params) {
    override fun specFor(key: String): DrainSpec = Drains.RETAG

    companion object {
        const val KEY_TAGGED = "tagged"
    }
}
