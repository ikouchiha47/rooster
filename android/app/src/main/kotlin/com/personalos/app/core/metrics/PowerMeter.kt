package com.personalos.app.core.metrics

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock

/**
 * Energy drawn during a window, read from the battery's own counters.
 *
 * **Whole device, not per app.** Android exposes no per-process energy figure, so
 * this says "the phone drew this much while the ML step ran" — useful as a trend
 * across runs on one device, never as an attribution.
 *
 * Some devices report `0` or `Long.MIN_VALUE` for the energy counter. Then this
 * returns null rather than a fabricated number: a missing measurement is honest,
 * and a zero that means "unsupported" is not.
 */
class PowerMeter(
    context: Context,
    /** Read per sample, so a Settings toggle applies without re-creating this. */
    private val enabled: () -> Boolean = { false },
) {
    private val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    /** A point sample: the counters at [elapsedRealtime]. */
    data class Sample(
        val energyNwh: Long?,
        val chargeUah: Long?,
        val elapsedRealtime: Long,
    )

    /** What happened between two samples. [energyNwh] is positive for consumption. */
    data class Delta(
        val energyNwh: Long?,
        val chargeUah: Long?,
        val elapsedMs: Long,
    )

    /** Battery level 0..100, or null when the device does not report it. */
    fun levelPercent(): Int? =
        battery
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    /** Charging or full; null when unknown. */
    fun isCharging(): Boolean? =
        battery
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            ?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }

    /** A sample, or null when this device exposes neither counter. */
    fun sample(): Sample? {
        if (!enabled()) return null
        val manager = battery ?: return null
        val energy =
            manager
                .getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                .takeIf { it != 0L && it != Long.MIN_VALUE }
        val charge =
            manager
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                .takeIf { it > 0 }
                ?.toLong()
        if (energy == null && charge == null) return null
        return Sample(energy, charge, SystemClock.elapsedRealtime())
    }

    /**
     * The difference between [from] and [to]. The energy counter **decreases** as
     * energy is used, so the result is negated into "consumed". Null when neither
     * counter is present at both ends — no partial guess.
     */
    fun delta(
        from: Sample,
        to: Sample,
    ): Delta? {
        val energy = if (from.energyNwh != null && to.energyNwh != null) from.energyNwh - to.energyNwh else null
        val charge = if (from.chargeUah != null && to.chargeUah != null) from.chargeUah - to.chargeUah else null
        if (energy == null && charge == null) return null
        return Delta(energy, charge, to.elapsedRealtime - from.elapsedRealtime)
    }
}
