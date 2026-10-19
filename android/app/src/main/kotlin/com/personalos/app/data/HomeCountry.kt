package com.personalos.app.data

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The country numbers are read in — **one stored value, never a per-call probe**.
 *
 * A probe answers differently with no SIM, while roaming, or on a different active
 * SIM, so the same number could canonicalise one way today and another way
 * tomorrow — an identity that moves splits a thread as readily as it merges one.
 * So it is resolved **once**, stored, and the user can change it.
 */
class HomeCountry(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("home_country", Context.MODE_PRIVATE)

    /** The stored ISO country; seeded from the device on first read, then fixed. */
    fun iso(): String = prefs.getString(KEY, null) ?: seed().also { prefs.edit().putString(KEY, it).apply() }

    fun set(iso: String) {
        prefs.edit().putString(KEY, iso.trim().uppercase()).apply()
    }

    /** SIM first (what messages actually arrive through), then the device's locale. */
    private fun seed(): String =
        runCatching {
            appContext
                .getSystemService(TelephonyManager::class.java)
                ?.simCountryIso
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.length == 2 }
        }.getOrNull()
            ?: Locale
                .getDefault()
                .country
                .trim()
                .uppercase()
                .takeIf { it.length == 2 }
                .orEmpty()

    private companion object {
        const val KEY = "iso"
    }
}
