package com.personalos.app.core.sms

/** One active SIM: the platform subscription id, a display label, and its slot. */
data class Sim(
    val subscriptionId: Int,
    val label: String,
    val slotIndex: Int,
    val iccId: String? = null,
)

/** The active SIMs on this device; empty when the grant is missing or there are none. */
interface SimDirectory {
    suspend fun active(): List<Sim>
}
