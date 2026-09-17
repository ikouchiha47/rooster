package com.personalos.app.data.adapters

import android.util.Log
import com.personalos.app.core.net.Http
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.sources.DeviceSpec
import com.personalos.app.core.sources.FxSpec
import com.personalos.app.core.sources.SmsSpec
import com.personalos.app.core.sources.WeatherSpec
import com.personalos.app.data.SourceEntity

/** `kind = "sms"`: SMS polling lives in `SmsSource`; the adapter only names the identity. */
class SmsKindAdapter : KindAdapter {
    override val kindId: String = "sms"

    override suspend fun ingest(
        source: SourceEntity,
        now: Long,
    ): Int = 0

    fun specOf(source: SourceEntity): SmsSpec = parseSpec(source.specJson) as SmsSpec
}

/** `kind = "weather"`: one place → one `weather:<slug>` observation per bucket. */
class WeatherKindAdapter(
    private val observations: ObservationWriter,
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
        val identity =
            com.personalos.app.core.sources.SourceKeys
                .sourceFor(source.id, spec)
        val raw =
            runCatching { fetch(WeatherUrl.url(spec.lat, spec.lon)) }
                .onFailure { Log.w(TAG, "weather fetch failed: ${spec.place}", it) }
                .getOrNull() ?: return 0
        val current = WeatherCurrentParser.parse(raw) ?: return 0
        observations.write(
            ObservationWriter.Observation(
                identity = identity,
                timestamp = now,
                title = spec.place,
                content = "${current.tempC}C",
                topicTag = "weather",
                fields =
                    mapOf(
                        "temp_c" to FieldValue.Num(current.tempC),
                        "rain_mm" to FieldValue.Num(current.rainMm),
                    ),
            ),
            now = now,
        )
        return 1
    }

    private companion object {
        const val TAG = "WeatherAdapter"
    }
}

/** `kind = "fx"`: one pair → one `fx:<slug>` observation per bucket. */
class FxKindAdapter(
    private val observations: ObservationWriter,
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
        val identity =
            com.personalos.app.core.sources.SourceKeys
                .sourceFor(source.id, spec)
        val raw =
            runCatching { fetch(FxRateParser.url(spec.pair)) }
                .onFailure { Log.w(TAG, "fx fetch failed: ${spec.pair}", it) }
                .getOrNull() ?: return 0
        val rate = FxRateParser.parse(raw, spec.pair) ?: return 0
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
