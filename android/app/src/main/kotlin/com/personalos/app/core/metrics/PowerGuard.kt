package com.personalos.app.core.metrics

/**
 * Stops long ML work before it drains the phone.
 *
 * A policy, not telemetry: it reads the level even when the telemetry log is off,
 * because pausing on a flat battery is about the device, not about measuring it.
 *
 * `null` level (a device that does not report it) means "do not pause" — refusing
 * to work because a sensor is missing would be worse than the work.
 */
class PowerGuard(
    private val meter: PowerMeter,
    /** Pause below this percentage, unless charging. */
    val minPercent: Int = 20,
) {
    fun shouldPause(): Boolean {
        val level = meter.levelPercent() ?: return false
        return level < minPercent && meter.isCharging() == false
    }
}
