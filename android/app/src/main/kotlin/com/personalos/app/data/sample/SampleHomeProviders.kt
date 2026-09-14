package com.personalos.app.data.sample

import com.personalos.app.core.Chars
import com.personalos.app.core.model.Accent
import com.personalos.app.core.model.Glance
import com.personalos.app.core.model.Money
import com.personalos.app.core.model.NowPlaying
import com.personalos.app.core.model.PinnedItem
import com.personalos.app.core.model.WeatherSnapshot
import com.personalos.app.core.provider.GlanceProvider
import com.personalos.app.core.provider.LocationProvider
import com.personalos.app.core.provider.NowPlayingProvider
import com.personalos.app.core.provider.PinnedProvider
import com.personalos.app.data.EventDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Sample implementations for the Home data points that have no real source yet.
 *
 * Replace one at a time in [com.personalos.app.core.AppContainer]; nothing in
 * the UI needs to change.
 *
 * Real sources when available:
 *  - moneyOutToday -> parsed from SMS (SmsClassifier + amount extraction)
 *  - nowPlaying    -> radio-browser.info
 *  - pinned        -> locally ranked recent events
 */

private const val DAY_MS = 86_400_000L

class SampleGlanceProvider(
    private val dao: EventDao,
) : GlanceProvider {
    override fun observe(): Flow<Glance> {
        val since = System.currentTimeMillis() - DAY_MS
        return combine(
            dao.observeCount(),
            dao.observeCountSince("inbox", since),
            dao.observeCountAllSince(since),
        ) { total, alerts, events24h ->
            Glance(
                moneyOutToday = Money(amount = 12_480.0, note = "1 debit"),
                alertsFired24h = alerts,
                events24h = events24h,
                totalEvents = total,
            )
        }
    }
}

class SampleNowPlayingProvider : NowPlayingProvider {
    override fun observe(): Flow<NowPlaying?> =
        flowOf(
            NowPlaying(station = "NHK World", place = "Tokyo", bitrateKbps = 128),
        )
}

/**
 * No present location yet. The real seam (GPS / network / last-known) is wired
 * in [com.personalos.app.core.AppContainer] when it lands; until then Weather
 * shows a placeholder in the present slot instead of borrowing a configured
 * place for it.
 */
class SampleLocationProvider : LocationProvider {
    override fun observe(): Flow<WeatherSnapshot?> = flowOf(null)
}

class SamplePinnedProvider : PinnedProvider {
    override fun observe(): Flow<List<PinnedItem>> =
        flowOf(
            listOf(
                PinnedItem(
                    id = "pinned-1",
                    label = "Fired ${Chars.MIDDLE_DOT} R-01 ${Chars.MIDDLE_DOT} 09:42",
                    title = "Sample Bank: ${Chars.RUPEE}12,480 debited from a/c ${Chars.BULLET}${Chars.BULLET}4021",
                    meta = "Sample Electronics, Indiranagar ${Chars.MIDDLE_DOT} push sent",
                    accent = Accent.TEAL,
                ),
                PinnedItem(
                    id = "pinned-2",
                    label = "FX move ${Chars.MIDDLE_DOT} USD/INR",
                    title = "95.56 ${Chars.ARROW_RIGHT} 95.12",
                    meta = "${Chars.MINUS}0.5% ${Chars.MIDDLE_DOT} below 96.00 target",
                    accent = Accent.MUSTARD,
                ),
                PinnedItem(
                    id = "pinned-3",
                    label = "News match ${Chars.MIDDLE_DOT} infra",
                    title = "Kolkata Metro clears safety trial",
                    meta = "Relevance 94 ${Chars.MIDDLE_DOT} 09:31",
                    accent = Accent.INDIGO,
                ),
            ),
        )
}
