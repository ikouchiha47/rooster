package com.personalos.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether the OS is allowed to defer our background work.
 *
 * Doze and App Standby are why a long retag never ran in the background. Asking to
 * be exempted is the smallest fix: no permanent notification (unlike a foreground
 * service), just a one-time system dialog the user accepts.
 */
object BatteryOptimization {
    fun isExempt(context: Context): Boolean =
        context
            .getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** The system dialog that asks the user to exempt this app. */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
}
