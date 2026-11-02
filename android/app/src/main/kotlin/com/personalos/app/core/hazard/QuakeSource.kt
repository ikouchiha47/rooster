package com.personalos.app.core.hazard

/**
 * One source of seismic events. A volcano feed is a separate source later; it
 * does not belong inside the weather or climate providers.
 */
interface QuakeSource {
    val id: String

    suspend fun fetch(): List<Quake>
}
