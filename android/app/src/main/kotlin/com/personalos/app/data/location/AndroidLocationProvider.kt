package com.personalos.app.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.personalos.app.core.model.WeatherSnapshot
import com.personalos.app.core.provider.LocationProvider
import com.personalos.app.data.remote.OpenMeteoWeatherProvider
import com.personalos.app.data.remote.WeatherLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The device's present location, as weather.
 *
 * Deliberately **last-known first**: a weather card should appear immediately
 * rather than wait on a GPS fix, and the network provider's last-known position is
 * usually accurate to a city. There is no reverse geocoding (ADR 0001 - runtime
 * geocoders returned wrong places), so the location is labelled generically and
 * its coordinates go straight to Open-Meteo, which needs no place name.
 *
 * Emits **null** - never a guess - when permission is missing or no fix is known.
 * That is what keeps the present slot showing an honest placeholder instead of
 * borrowing one of the configured places.
 */
class AndroidLocationProvider(
    private val context: Context,
    private val weather: OpenMeteoWeatherProvider,
) : LocationProvider {
    override fun observe(): Flow<WeatherSnapshot?> =
        flow {
            val fix = lastKnown()
            if (fix == null) {
                Log.i(TAG, "no present location (missing permission or no fix)")
                emit(null)
                return@flow
            }

            Log.i(TAG, "present fix via ${fix.provider}, age ${ageSeconds(fix)}s")
            emit(
                weather.snapshotAt(
                    WeatherLocation(PRESENT_LABEL, fix.latitude, fix.longitude),
                ),
            )
        }.flowOn(Dispatchers.IO)

    private fun ageSeconds(location: Location): Long = (System.currentTimeMillis() - location.time) / 1000

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** The freshest last-known fix across every enabled provider, if any. */
    private fun lastKnown(): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        return runCatching {
            manager.allProviders
                .mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }.maxByOrNull { it.time }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "PresentLocation"
        const val PRESENT_LABEL = "Present location"
    }
}
