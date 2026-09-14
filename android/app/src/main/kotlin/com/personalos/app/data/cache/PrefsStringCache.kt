package com.personalos.app.data.cache

import android.content.Context
import com.personalos.app.core.cache.StringCache

/** SharedPreferences-backed [StringCache]. Values are small JSON payloads. */
class PrefsStringCache(
    context: Context,
) : StringCache {
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
}
