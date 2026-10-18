package com.personalos.app.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.personalos.app.core.cache.ObservableStringCache
import com.personalos.app.core.cache.StringCache
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** SharedPreferences-backed [ObservableStringCache]. Values are small JSON payloads. */
class PrefsStringCache(
    context: Context,
) : ObservableStringCache {
    private val prefs = context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE)

    override fun read(key: String): StringCache.Entry? {
        val value = prefs.getString("$key.value", null) ?: return null
        return StringCache.Entry(value = value, at = prefs.getLong("$key.at", 0L))
    }

    override fun write(
        key: String,
        value: String,
        at: Long,
    ) {
        prefs
            .edit()
            .putString("$key.value", value)
            .putLong("$key.at", at)
            .apply()
    }

    override fun observe(key: String): Flow<StringCache.Entry?> =
        callbackFlow {
            // SharedPreferences keeps only a weak reference to its listeners, so the
            // listener is held strongly here, for as long as the collection runs.
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
                    if (changed == "$key.value" || changed == "$key.at") trySend(read(key))
                }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            // The value now, so a subscriber is never blank until the next write.
            trySend(read(key))
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
}
