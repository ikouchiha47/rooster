package com.personalos.app.data.adapters

import android.util.Log
import com.personalos.app.core.net.Http
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.sources.DeviceSpec
import com.personalos.app.core.sources.FxSpec
import com.personalos.app.core.sources.SmsSpec
import com.personalos.app.core.sources.SourceKeys
import com.personalos.app.core.sources.WeatherSpec
import com.personalos.app.data.SourceEntity
import com.personalos.app.data.remote.FrankfurterFxProvider
import com.personalos.app.data.remote.OpenMeteoWeatherProvider

/** `kind = "sms"`: SMS polling lives in `SmsSource`; the adapter only names the identity. */
class SmsKindAdapter : KindAdapter {
    override val kindId: String = "sms"

    override suspend fun ingest(
        source: SourceEntity,
        now: Long,
    ): Int = 0

    fun specOf(source: SourceEntity): SmsSpec = parseSpec(source.specJson) as SmsSpec
}

/**
 * `kind = "weather"`: one place → one current observation per hour bucket plus
 * one keyed `weather:<slug>:fc:<date>` row per forecast day.
 *
 * Reads through [CachedBody] on the **provider's own cache key and window**, so
 * opening a screen, the hourly worker and a manual sync all share one fetch per
 * place per 6 hours — and the body is the same one `WeatherProvider.observe()`
 * serves, so the two can never disagree.
 */
class WeatherKindAdapter(
    private val observations: ObservationWriter,
    private val cache: CachedBody,
    private val fetch: (String) -> String = { url -> Http.getText(url) },
) : KindAdapter {
    override val kindId: String = "weather"

    override suspend fun ingest(
        source: SourceEntity,
        now: Long,
    ): Int {
        val spec =
            runCatching { parseSpec(source.specJson) as WeatherSpec }
                .onFailure { Log.w(TAG, "bad weather spec for ${source.id}", it) }
                .getOrNull() ?: return 0
        val identity = SourceKeys.sourceFor(source.id, spec)
        val raw =
            cache.get(
                key = OpenMeteoWeatherProvider.cacheKey(spec.place),
                ttlMs = OpenMeteoWeatherProvider.REFRESH_MS,
                now = now,
            ) {
                runCatching { fetch(WeatherUrl.url(spec.lat, spec.lon)) }
                    .onFailure { Log.w(TAG, "weather fetch failed: ${spec.place}", it) }
                    .getOrNull()
            } ?: return 0

        var written = 0
        val current = WeatherCurrentParser.parse(raw)
        if (current != null) {
            val fields = LinkedHashMap<String, FieldValue>()
            fields["temp_c"] = FieldValue.Num(current.tempC)
            fields["rain_mm"] = FieldValue.Num(current.rainMm)
            fields["humidity_pct"] = FieldValue.Num(current.humidityPct)
            fields["wind_kmh"] = FieldValue.Num(current.windKmh)
            current.weatherCode?.let { fields["weather_code"] = FieldValue.Num(it) }
            observations.write(
                ObservationWriter.Observation(
                    identity = identity,
                    timestamp = now,
                    title = spec.place,
                    content = "${current.tempC}C",
                    topicTag = "weather",
                    fields = fields,
                ),
                now = now,
            )
            written++
        }
        for (day in WeatherWeekParser.parse(raw)) {
            val dayIdentity = "$identity:fc:${day.date}"
            val fields = LinkedHashMap<String, FieldValue>()
            fields["temp_max_c"] = FieldValue.Num(day.maxC)
            fields["temp_min_c"] = FieldValue.Num(day.minC)
            fields["rain_chance"] = FieldValue.Num(day.rainChance)
            day.weatherCode?.let { fields["weather_code"] = FieldValue.Num(it) }
            observations.writeKeyed(
                dayIdentity,
                ObservationWriter.Observation(
                    identity = dayIdentity,
                    timestamp = now,
                    title = spec.place,
                    content = day.date,
                    topicTag = "weather",
                    fields = fields,
                ),
                now = now,
            )
            written++
        }
        return written
    }

    private companion object {
        const val TAG = "WeatherAdapter"
    }
}

/**
 * `kind = "fx"`: one pair → one `fx:<slug>` observation per bucket.
 *
 * Every pair derives from the provider's **one cached USD-base body**, so three
 * tracked pairs cost one request per window, not three per refresh, and a
 * missing leg is absent rather than zero.
 */
class FxKindAdapter(
    private val observations: ObservationWriter,
    private val cache: CachedBody,
    private val fetch: (String) -> String = { url -> Http.getText(url) },
) : KindAdapter {
    override val kindId: String = "fx"

    override suspend fun ingest(
        source: SourceEntity,
        now: Long,
    ): Int {
        val spec =
            runCatching { parseSpec(source.specJson) as FxSpec }
                .onFailure { Log.w(TAG, "bad fx spec for ${source.id}", it) }
                .getOrNull() ?: return 0
        val identity = SourceKeys.sourceFor(source.id, spec)
        val raw =
            cache.get(
                key = FrankfurterFxProvider.CACHE_KEY,
                ttlMs = FrankfurterFxProvider.REFRESH_MS,
                now = now,
            ) {
                runCatching { fetch(FxRateParser.url()) }
                    .onFailure { Log.w(TAG, "fx fetch failed: ${spec.pair}", it) }
                    .getOrNull()
            } ?: return 0
        val rate = FxRateParser.rateFor(spec.pair, FxRateParser.parseAll(raw)) ?: return 0
        observations.write(
            ObservationWriter.Observation(
                identity = identity,
                timestamp = now,
                title = spec.pair.uppercase(),
                content = rate.toString(),
                topicTag = "finance",
                fields = mapOf("rate" to FieldValue.Num(rate)),
            ),
            now = now,
        )
        return 1
    }

    private companion object {
        const val TAG = "FxAdapter"
    }
}

/** `kind = "device"`: seeded local signals; no fetch yet, never a crash. */
class DeviceKindAdapter : KindAdapter {
    override val kindId: String = "device"

    override suspend fun ingest(
        source: SourceEntity,
        now: Long,
    ): Int = 0

    fun specOf(source: SourceEntity): DeviceSpec = parseSpec(source.specJson) as DeviceSpec
}
