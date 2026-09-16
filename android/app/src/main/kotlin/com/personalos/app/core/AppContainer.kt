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
import com.personalos.app.data.ContentResolverSmsReader
import com.personalos.app.data.FieldWriter
import com.personalos.app.data.MentionWriter
import com.personalos.app.data.PartySeeder
import com.personalos.app.data.PlacesSeeder
import com.personalos.app.data.PrefsSmsSyncMark
import com.personalos.app.data.RulePreviewLoader
import com.personalos.app.data.RulePreviewer
import com.personalos.app.data.RuleRepository
import com.personalos.app.data.RuleSeeder
import com.personalos.app.data.RuleWriter
import com.personalos.app.data.SmsSource
import com.personalos.app.data.SourceRepository
import com.personalos.app.data.SourceSeeder
import com.personalos.app.data.TagWriter
import com.personalos.app.data.ThemeRepository
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

    /** Typed extras (`amount`, `sender`, ...), frozen on first write (ADR §13). */
    val fieldWriter: FieldWriter = FieldWriter(database.itemFieldDao())

    /**
     * Single owner of the theme fact: seeds nothing, stores the choice in the
     * shared prefs store. Settings writes through it; the app seeds the
     * runtime theme from it at startup.
     */
    val themeRepository: ThemeRepository = ThemeRepository(cache)

    /** Seeds the gazetteer once; afterwards a single `COUNT(*)` no-op. */
    val placesSeeder: PlacesSeeder =
        PlacesSeeder(
            database.placeDao(),
            openAsset = { context.applicationContext.assets.open(PLACES_ASSET) },
        )

    /** Seeds the party registry once; afterwards a single `COUNT(*)` no-op. */
    val partySeeder: PartySeeder =
        PartySeeder(
            database.partyDao(),
            database.partySourceDao(),
        )

    /**
     * Single owner of the sources fact (ADR 0003): seeds write through it,
     * Settings will write through it, the ingestor reads through it.
     */
    val sourceRepository: SourceRepository = SourceRepository(database.sourceDao())

    /** Seeds the v1 source set once; afterwards a single `COUNT(*)` no-op. */
    val sourceSeeder: SourceSeeder = SourceSeeder(database.sourceDao())

    /** Seeds the v1 rule set once; afterwards a single `COUNT(*)` no-op. */
    val ruleSeeder: RuleSeeder = RuleSeeder(database.ruleDao())

    /**
     * Single owner of the rules fact (ADR 0003): the authoring screen edits
     * through it, the writer evaluates what is stored.
     */
    val ruleRepository: RuleRepository = RuleRepository(database.ruleDao())

    /**
     * Writes `item_rules` matches (ADR 0003 §7–§10). Reads tags, mentions and
     * typed fields from the store, so the store stays the one owner of those
     * facts and evaluation sees exactly what is persisted.
     */
    val ruleWriter: RuleWriter =
        RuleWriter(
            database.ruleDao(),
            database.itemRuleDao(),
            database.itemTagDao(),
            database.mentionDao(),
            database.itemFieldDao(),
        )

    /**
     * The authoring dry run: a bounded, indexed candidate load over stored
     * history handed to [ruleWriter], persisting nothing (ADR §12, R4). One
     * composition point, so the authoring screen invents no plumbing of its own.
     */
    val rulePreview: RulePreviewer =
        RulePreviewer(
            RulePreviewLoader(database.eventDao()),
            ruleWriter,
        )

    /** Refreshes the party registry from the per-country list pages. */
    val partySync: com.personalos.app.data.remote.parties.PartySyncer =
        com.personalos.app.data.remote.parties.PartySyncer(
            database.partyDao(),
            database.partySourceDao(),
        )

    /** Writes place/party mentions for ingested items. Stored, not displayed. */
    val mentionWriter: MentionWriter =
        MentionWriter(
            database.mentionDao(),
            loadPlaces = { database.placeDao().all() },
            loadParties = {
                database.partyDao().all().map { row ->
                    com.personalos.app.core.mention.PartyEntry(
                        slug = row.slug,
                        country = row.country,
                        name = row.name,
                        aliases = row.aliases.split('|'),
                        stronghold = row.stronghold,
                        recognition = row.recognition,
                    )
                }
            },
        )

    /**
     * The single SMS ingest path. The Activity's lifecycle observer calls its
     * suspend entry point while foregrounded; `SmsSyncWorker` calls the same one
     * on a periodic background schedule. One instance per coordinate, but the
     * high-water mark lives in SharedPreferences, so separate instances (the
     * worker builds its own container) still share it.
     */
    val smsSource: SmsSource =
        SmsSource(
            ContentResolverSmsReader(context.applicationContext),
            PrefsSmsSyncMark(context.applicationContext),
            database.eventDao(),
            tagWriter,
            mentionWriter,
            fieldWriter,
            ruleWriter,
        )

    /** Backfills tags for items that predate the active tagger (docs §11.5). */
    val retagger: Retagger =
        Retagger(
            database.eventDao(),
            tagWriter,
            cache,
            tagger,
            sourceTags = {
                runCatching {
                    database
                        .sourceDao()
                        .enabled()
                        .mapNotNull { row ->
                            val spec =
                                runCatching {
                                    com.personalos.app.core.sources.SourceSpecs
                                        .parse(row.kind, row.specJson)
                                }.getOrNull() ?: return@mapNotNull null
                            // Every source owns its source string (see SourceKeys),
                            // so retagging resolves user RSS rows the same way it
                            // resolves search rows. Seeded RSS rows map sources
                            // that never receive items (the catalog drives those
                            // URLs), which is harmless.
                            val source =
                                com.personalos.app.core.sources.SourceKeys
                                    .sourceFor(row.id, spec)
                            source to spec.tags
                        }.toMap()
                }.getOrDefault(emptyMap())
            },
        )

    /**
     * Ranks content by place. Temporary rung: no gazetteer or home places exist
     * yet, so this reports a single tier rather than a ranking it cannot compute
     * (docs §9). Swap here when places land - tiles do not change.
     */
    val placeRanker: PlaceRanker = RecencyPlaceRanker()

    val feeds: FeedIngestor =
        FeedIngestor(
            database.eventDao(),
            cache,
            tagWriter,
            mentionWriter,
            ruleWriter,
            loadSources = { sourceRepository.enabledSources() },
        )

    /**
     * Fills in summaries for items whose feed shipped none - notably Indian
     * Express, whose `<description>` is empty for every item. Bounded batches;
     * see [ArticleEnricher]. Items whose text grows are handed to the rule
     * writer, which re-evaluates only text-predicate rules (ADR §10, R3).
     */
    val enricher: ArticleEnricher =
        ArticleEnricher(
            database.eventDao(),
            onEnriched = { seeds -> ruleWriter.reevaluateEnriched(seeds) },
        )

    /** Shared sync state: drives the in-tile progress bar and the `Sync` log tag. */
    val sync: SyncStatus = SyncStatus()

    val nowPlaying: NowPlayingProvider = SampleNowPlayingProvider()
    val pinned: PinnedProvider = SamplePinnedProvider()

    private companion object {
        const val PLACES_ASSET = "places.dat"
    }
}
