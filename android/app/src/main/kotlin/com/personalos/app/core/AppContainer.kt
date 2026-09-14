package com.personalos.app.core

import android.content.Context
import com.personalos.app.core.place.PlaceRanker
import com.personalos.app.core.place.RecencyPlaceRanker
import com.personalos.app.core.provider.FxProvider
import com.personalos.app.core.provider.GlanceProvider
import com.personalos.app.core.provider.LocationProvider
import com.personalos.app.core.provider.NowPlayingProvider
import com.personalos.app.core.provider.PinnedProvider
import com.personalos.app.core.provider.WeatherProvider
import com.personalos.app.core.sync.SyncStatus
import com.personalos.app.core.tag.BundledTermSource
import com.personalos.app.core.tag.HeuristicTagger
import com.personalos.app.core.tag.Retagger
import com.personalos.app.core.tag.Tagger
import com.personalos.app.core.tag.TermStore
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.MentionWriter
import com.personalos.app.data.PlacesSeeder
import com.personalos.app.data.TagWriter
import com.personalos.app.data.cache.PrefsStringCache
import com.personalos.app.data.location.AndroidLocationProvider
import com.personalos.app.data.remote.ArticleEnricher
import com.personalos.app.data.remote.FeedIngestor
import com.personalos.app.data.remote.FrankfurterFxProvider
import com.personalos.app.data.remote.OpenMeteoWeatherProvider
import com.personalos.app.data.remote.WeatherLocation
import com.personalos.app.data.sample.SampleGlanceProvider
import com.personalos.app.data.sample.SampleNowPlayingProvider
import com.personalos.app.data.sample.SamplePinnedProvider

/**
 * Composition root - the single place where provider implementations are chosen.
 *
 * Real sources are wired where a genuinely free one exists:
 *  - weather -> Open-Meteo (free, keyless, week cached)
 *  - fx      -> Frankfurter / ECB (free, keyless, cached)
 *  - feeds   -> RSS/Atom from the bundled catalog (see docs/research)
 *  - glance  -> Room counts + sample money (parsing not built yet)
 *  - radio   -> sample (radio-browser.info adapter not built yet)
 *  - pinned  -> sample (local ranking not built yet)
 */
class AppContainer(
    context: Context,
) {
    private val database = AppDatabase.getInstance(context)
    private val cache = PrefsStringCache(context)

    /** Bundled coordinates; no runtime geocoding (see ADR 0001). */
    private val weatherLocations =
        listOf(
            WeatherLocation("Kolkata", 22.5726, 88.3639),
            WeatherLocation("Bengaluru", 12.9716, 77.5946),
            WeatherLocation("Berhampore", 24.1049, 88.2516),
        )

    val glance: GlanceProvider = SampleGlanceProvider(database.eventDao())
    val fx: FxProvider = FrankfurterFxProvider(cache)

    /**
     * Held concretely as well as through [WeatherProvider], because the present
     * location needs weather at an arbitrary coordinate rather than a configured
     * place.
     */
    private val openMeteo = OpenMeteoWeatherProvider(weatherLocations, cache)

    val weather: WeatherProvider = openMeteo

    /**
     * Where the device is now. A separate slot from [weatherLocations]: it is not a
     * place you configured, so it must never consume one. Reads the platform's
     * last-known fix, and reports null when there is no permission or no fix -
     * which is what makes the Weather screen show an honest placeholder.
     */
    val presentLocation: LocationProvider = AndroidLocationProvider(context, openMeteo)

    /**
     * Term sources are merged here, so a future user- or remotely-sourced vocabulary
     * is an extra entry in this list rather than a change to the tagger
     * (docs/CODE-DESIGN-GUIDELINES.md §2).
     */
    private val terms = TermStore(listOf(BundledTermSource))

    /**
     * The active tagger - the single swap point for the strategy ladder
     * (heuristic now; embed/model/cascade later). See docs/ARCHITECTURE.md §10.
     */
    val tagger: Tagger = HeuristicTagger(terms::lexicon)

    val tagWriter: TagWriter = TagWriter(database.itemTagDao(), tagger)

    /** Seeds the gazetteer once; afterwards a single `COUNT(*)` no-op. */
    val placesSeeder: PlacesSeeder =
        PlacesSeeder(
            database.placeDao(),
            openAsset = { context.applicationContext.assets.open(PLACES_ASSET) },
        )

    /** Writes place/party mentions for ingested items. Stored, not displayed. */
    val mentionWriter: MentionWriter =
        MentionWriter(database.mentionDao(), loadPlaces = { database.placeDao().all() })

    /** Backfills tags for items that predate the active tagger (docs §11.5). */
    val retagger: Retagger = Retagger(database.eventDao(), tagWriter, cache, tagger)

    /**
     * Ranks content by place. Temporary rung: no gazetteer or home places exist
     * yet, so this reports a single tier rather than a ranking it cannot compute
     * (docs §9). Swap here when places land - tiles do not change.
     */
    val placeRanker: PlaceRanker = RecencyPlaceRanker()

    val feeds: FeedIngestor = FeedIngestor(database.eventDao(), cache, tagWriter, mentionWriter)

    /**
     * Fills in summaries for items whose feed shipped none - notably Indian
     * Express, whose `<description>` is empty for every item. Bounded batches;
     * see [ArticleEnricher].
     */
    val enricher: ArticleEnricher = ArticleEnricher(database.eventDao())

    /** Shared sync state: drives the in-tile progress bar and the `Sync` log tag. */
    val sync: SyncStatus = SyncStatus()

    val nowPlaying: NowPlayingProvider = SampleNowPlayingProvider()
    val pinned: PinnedProvider = SamplePinnedProvider()

    private companion object {
        const val PLACES_ASSET = "places.dat"
    }
}
