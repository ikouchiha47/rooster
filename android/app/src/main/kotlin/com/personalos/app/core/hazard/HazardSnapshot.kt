package com.personalos.app.core.hazard

/**
 * The last known hazard picture.
 *
 * [fetchedAt] is the last **successful** fetch, not the last attempt, so a
 * failed refresh leaves the previous timestamp and data in place. [error] is
 * the most recent failure, cleared by the next success.
 */
data class HazardSnapshot(
    val quakes: List<Quake> = emptyList(),
    val fetchedAt: Long? = null,
    val error: String? = null,
) {
    val hasData: Boolean get() = fetchedAt != null
}
