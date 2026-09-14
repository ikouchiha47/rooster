package com.personalos.app.data.remote

import android.util.Log
import com.personalos.app.core.cache.StringCache
import com.personalos.app.core.model.FxRate
import com.personalos.app.core.net.Http
import com.personalos.app.core.provider.FxProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Frankfurter (ECB reference rates; free, keyless).
 *
 * One call with base USD gives USD/INR directly and lets the cross rates be
 * derived: EUR/INR = USD/INR / USD/EUR. Cached, so opening the screen does not
 * hit the network unless the cached value is old.
 */
class FrankfurterFxProvider(
    private val cache: StringCache,
    private val refreshAfterMs: Long = DEFAULT_REFRESH_MS,
) : FxProvider {
    override fun observe(): Flow<List<FxRate>> =
        flow {
            val now = System.currentTimeMillis()
            val cached = cache.read(KEY)

            if (cached != null) emit(parse(cached.value))

            if (cached == null || now - cached.at >= refreshAfterMs) {
                val fetched = runCatching { Http.getText(URL) }.getOrNull()
                if (fetched != null) {
                    cache.write(KEY, fetched, now)
                    emit(parse(fetched))
                } else if (cached == null) {
                    emit(FALLBACK)
                }
            }
        }.flowOn(Dispatchers.IO)

    private fun parse(raw: String): List<FxRate> =
        try {
            val rates =
                Http.json
                    .parseToJsonElement(raw)
                    .jsonObject["rates"]
                    ?.jsonObject
                    ?: error("missing rates")

            val usdInr = rates["INR"]?.jsonPrimitive?.double ?: error("missing USD/INR")
            val usdEur = rates["EUR"]?.jsonPrimitive?.double
            val usdGbp = rates["GBP"]?.jsonPrimitive?.double

            buildList {
                add(FxRate("usd-inr", "USD/INR", usdInr, "ECB"))
                usdEur?.let { add(FxRate("eur-inr", "EUR/INR", usdInr / it, "ECB")) }
                usdGbp?.let { add(FxRate("gbp-inr", "GBP/INR", usdInr / it, "ECB")) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse failed", e)
            FALLBACK
        }

    private companion object {
        const val TAG = "Fx"
        const val KEY = "fx:usd-base"
        const val URL = "https://api.frankfurter.dev/v1/latest?base=USD&symbols=INR,EUR,GBP"
        const val DEFAULT_REFRESH_MS = 6L * 60 * 60 * 1000

        /** Last known good values, so the cell is never empty offline. */
        val FALLBACK =
            listOf(
                FxRate("usd-inr", "USD/INR", 95.56, "cached"),
                FxRate("eur-inr", "EUR/INR", 110.78, "cached"),
                FxRate("gbp-inr", "GBP/INR", 129.08, "cached"),
            )
    }
}
