package com.personalos.app.data.work

import android.content.Context
import com.personalos.app.data.cache.PrefsStringCache

/**
 * The backfill's **version** (an epoch), so "the latest enqueue wins" without any
 * cancel race.
 *
 * Enqueuing bumps the version and stamps every work unit with it. A worker whose
 * stamp is not the current version is stale and exits **before it does anything** —
 * crucially, before loading the model. So an old run can never load a second
 * model alongside the new one; it just falls away. [isCurrent] is cheap, so it is
 * also re-checked after the model load, to stop a run the moment it is superseded.
 */
object BackfillVersion {
    const val KEY = "backfill-version"

    private const val PREF = "backfill:version"

    /** The current version, or null when none has been set yet. */
    fun current(context: Context): Long? = PrefsStringCache(context).read(PREF)?.value?.toLongOrNull()

    /** Set and return a fresh version, superseding every older run. */
    fun bump(context: Context): Long {
        val version = System.currentTimeMillis()
        PrefsStringCache(context).write(PREF, version.toString(), version)
        return version
    }

    /** Whether [version] is still the one to run. */
    fun isCurrent(
        context: Context,
        version: Long,
    ): Boolean = current(context) == version
}
