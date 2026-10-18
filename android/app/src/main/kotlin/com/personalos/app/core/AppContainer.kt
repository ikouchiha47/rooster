package com.personalos.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.personalos.app.core.place.PlaceRanker
import com.personalos.app.core.place.RecencyPlaceRanker
import com.personalos.app.core.provider.FxProvider
import com.personalos.app.core.provider.GlanceProvider
import com.personalos.app.core.provider.LocationProvider
import com.personalos.app.core.provider.NowPlayingProvider
import com.personalos.app.core.provider.PinnedProvider
import com.personalos.app.core.provider.WeatherProvider
import com.personalos.app.core.sms.ContactDirectory
import com.personalos.app.core.sms.SmsSender
import com.personalos.app.core.sync.SyncStatus
import com.personalos.app.core.tag.HeuristicTagger
import com.personalos.app.core.tag.Retagger
import com.personalos.app.core.tag.TagVocabulary
import com.personalos.app.core.tag.Tagger
import com.personalos.app.core.tag.taxonomy.source
import com.personalos.app.data.AndroidSmsSender
import com.personalos.app.data.AppDatabase
import com.personalos.app.data.CATEGORY_TAXONOMY_ASSET
import com.personalos.app.data.CalendarRepository
import com.personalos.app.data.CatalogSeeder
import com.personalos.app.data.CatalogStore
import com.personalos.app.data.CategoryBackfill
import com.personalos.app.data.CategorySeeder
import com.personalos.app.data.CategoryStore
import com.personalos.app.data.ContentResolverSmsReader
import com.personalos.app.data.FieldWriter
import com.personalos.app.data.MentionWriter
import com.personalos.app.data.ObservationRepository
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
import com.personalos.app.data.adapters.DeviceKindAdapter
import com.personalos.app.data.adapters.FxKindAdapter
import com.personalos.app.data.adapters.ObservationWriter
import com.personalos.app.data.adapters.SmsKindAdapter
import com.personalos.app.data.adapters.WeatherKindAdapter
import com.personalos.app.data.adapters.adaptersByKind
import com.personalos.app.data.cache.PrefsStringCache
import com.personalos.app.data.contacts.AndroidContactDirectory
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
    private val context: Context,
) {
    private val database = AppDatabase.getInstance(context)
    private val cache = PrefsStringCache(context)

    /**
     * The user's time zone. Stored times are UTC instants; this is the zone the UI
     * converts them in (see `UserZone`). Restored from the store here, defaulting
     * to the device's and persisted on first run, so "today" is stable across
     * restarts and travel.
     */
    val zones: com.personalos.app.data.ZoneRepository =
        com.personalos.app.data.ZoneRepository(cache).also { repo ->
            com.personalos.app.core.time.UserZone
                .set(repo.id())
            if (repo.id() == null) {
                repo.save(
                    com.personalos.app.core.time.UserZone
                        .id(),
                )
            }
        }

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
     * The one taxonomy (bundled asset, with a downloaded copy merged over it).
     * Everything derives from it — the vocabulary, the tag groups/owners
     * ([schema]) and the feed categories — so one downloaded file updates them all.
     */
    private val taxonomy: com.personalos.app.core.tag.taxonomy.Taxonomy? =
        com.personalos.app.data.taxonomy.TaxonomyLoader
            .load(context, com.personalos.app.data.KnownModels.taxonomy.fileName)

    /** Groups and owners as data; falls back to the bundled defaults when unloaded. */
    private val schema: com.personalos.app.core.tag.TagSchema =
        taxonomy?.let {
            com.personalos.app.core.tag.TagSchema
                .of(it)
        }
            ?: com.personalos.app.core.tag.TagSchema.BUNDLED

    private val vocabulary: TagVocabulary =
        TagVocabulary(listOf(taxonomy?.source() ?: com.personalos.app.core.tag.BundledVocabulary))

    /** Re-read the vocabulary after a taxonomy download; the taggers read it lazily. */
    fun reloadVocabulary() {
        vocabulary.invalidate()
    }

    /** The feed directory: category → known feeds, so adding a feed is one tap. */
    val feedDirectory: com.personalos.app.core.feed.FeedDirectory? =
        com.personalos.app.data.feed.FeedDirectoryLoader
            .load(context)

    /** The categories a feed can be, for the picker (falls back to plain news). */
    val feedCategories: List<String> =
        feedDirectory?.categories?.map { it.category } ?: listOf("news")

    /** The known feeds suggested for a category. */
    fun suggestedFeeds(category: String): List<com.personalos.app.core.feed.DirectoryFeed> = feedDirectory?.feedsFor(category).orEmpty()

    private val heuristic: Tagger = HeuristicTagger(vocabulary::lexicon, schema)

    /**
     * The active tagger. Starts as the heuristic; [upgradeTagger] swaps in the
     * embedding policy tagger once the model is on disk. A stable reference, so the
     * writers below never re-bind and ingest never waits on a download.
     */
    private val switchable =
        com.personalos.app.core.tag
            .SwitchableTagger(heuristic)

    val tagger: Tagger = switchable

    /** Set once [upgradeTagger] succeeds, so later callers skip the model hash. */
    @Volatile
    private var upgraded = false

    val tagWriter: TagWriter = TagWriter(database.itemTagDao(), tagger)

    /**
     * Upgrades to [com.personalos.app.core.tag.PolicyTagger] — lexicon for curated
     * tags, EmbeddingGemma for contextual ones — when both files are present and
     * load. Returns whether it upgraded. A no-op (never a failure) when the model
     * is absent, so it is safe on every launch and after a download.
     */
    suspend fun upgradeTagger(): Boolean {
        // One-time. Several workers call this (core + one per family), and the check
        // below sha256-hashes 176 MB — so without this guard each worker re-hashed
        // the model and bogged the device. Once upgraded, it is a free no-op.
        if (upgraded) return true
        val model = com.personalos.app.data.KnownModels.embeddingGemmaOnnx
        val data = com.personalos.app.data.KnownModels.embeddingGemmaOnnxData
        val tokenizer = com.personalos.app.data.KnownModels.embeddingGemmaOnnxTokenizer
        val ready = com.personalos.mlsdk.ModelStatus.READY
        // The `.onnx_data` is part of the model: all three must be present, or the
        // session load would fail on the missing weights.
        if (models.status(model) != ready || models.status(data) != ready || models.status(tokenizer) != ready) return false
        val dir = models.dir()
        val embedder =
            com.personalos.app.data.models.OnnxEmbedder(
                modelFile = java.io.File(dir, model.fileName),
                tokenizerFile = java.io.File(dir, tokenizer.fileName),
            )
        if (!embedder.init()) return false
        // The ML rung is now nearest labelled examples (kNN), not description
        // cosine — measurably better on real data, and it improves as corrections
        // accrue. The lexicon rung is unchanged.
        switchable.use(
            com.personalos.app.core.tag.PolicyTagger(
                heuristic,
                com.personalos.app.core.tag.KnnTagger(
                    embedMany = embedder::embedMany,
                    examples =
                        com.personalos.app.data.tagging
                            .AssetExampleSource(context),
                ),
                schema,
            ),
        )
        // Point the `taggers` row at the tagger now running. Only the ingest paths
        // called this, so the store kept reporting the old tagger as active after a
        // swap and a retag — `item_tags_current` never showed the new tags.
        tagWriter.ensureActive()
        upgraded = true
        return true
    }

    /**
     * Removes model files the active bundle no longer names — the inactive
     * runtime's weights, or an abandoned `.part`. Idempotent, and it deletes only
     * files on disk, never a spec in code, so a runtime swap ([KnownModels]
     * repointed) leaves no second copy of the model behind. Returns how many went.
     */
    suspend fun pruneModels(): Int =
        models
            .evictUnlisted(
                (
                    com.personalos.app.data.KnownModels.embeddingGemmaBundle +
                        com.personalos.app.data.KnownModels.vocabularyBundle
                ).map { it.fileName },
            ).size

    /**
     * Background-job status: one snapshot per scheduled unique-work name,
     * observed from WorkManager. Screens derive rows from the snapshots; a
     * newly scheduled worker appears by registration data, not a screen edit.
     */
    val workerJobs: com.personalos.app.data.work.WorkerStatusRepository =
        com.personalos.app.data.work
            .WorkerStatusRepository(WorkManager.getInstance(context))

    /** The scheduler a rule edit enqueues through; one owner, WorkManager behind it. */
    private val ruleBackfillScheduler: com.personalos.app.data.RuleBackfillScheduler =
        com.personalos.app.data.RuleBackfillScheduler { ruleId, generation ->
            com.personalos.app.data.work.SyncScheduler
                .enqueueRuleBackfill(context, ruleId, generation)
        }

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

    /**
     * The single owner of the calendar fact: followed regions and their
     * observances. The Calendar tile and Travel's Calendar tab both read this
     * one instance, so they can never disagree on which regions are followed.
     */
    val calendarRepository: CalendarRepository =
        CalendarRepository(database.sourceDao(), database.calendarDao())

    /** The activity log behind the Radar Events tab — one run row per source per poll. */
    val syncRecorder: com.personalos.app.data.RoomSyncRecorder =
        com.personalos.app.data
            .RoomSyncRecorder(database.syncRunDao())

    /** ADR 0005 T6: seeds the facet catalog; afterwards a no-op. */
    val catalogSeeder: CatalogSeeder =
        CatalogSeeder(database.kindDao(), database.facetDao(), database.kindFacetDao())

    /** ADR 0005 T6: the single owner of the catalog fact. */
    val catalogStore: CatalogStore =
        CatalogStore(database.kindDao(), database.facetDao(), database.kindFacetDao())

    /** Seeds the v1 rule set once; afterwards a single `COUNT(*)` no-op. */
    val ruleSeeder: RuleSeeder = RuleSeeder(database.ruleDao())

    /**
     * Single owner of the rules fact (ADR 0003): the authoring screen edits
     * through it, the writer evaluates what is stored.
     */
    val ruleRepository: RuleRepository = RuleRepository(database.ruleDao(), ruleBackfillScheduler)

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
     * Re-evaluates one rule over recent history — the rule-side counterpart to
     * [retagger]. Bounded to the newest items, batched, and resumable; run from
     * a worker when a rule is added, edited, or swept on launch.
     */
    val ruleBackfill: com.personalos.app.core.rules.RuleBackfill =
        com.personalos.app.core.rules
            .RuleBackfill(
                database.ruleBackfillDao(),
                database.ruleDao(),
                writeMatches = { rule, seeds -> ruleWriter.writeFor(rule, seeds) },
                cache,
            )

    /** Saved items: one writer, one reader, over the flag on the item. */
    val bookmarks: com.personalos.app.data.BookmarkRepository =
        com.personalos.app.data
            .BookmarkRepository(database.bookmarkDao())

    /** Removing the app's stored copy of an item (device copy untouched). */
    val messageDeletes: com.personalos.app.data.MessageDeleteRepository =
        com.personalos.app.data
            .MessageDeleteRepository(database.messageDeleteDao())

    /**
     * Messages search: an FTS5 trigram read over SMS rows only (v19). The query
     * plan is built in `core/search`; this exposes the one call the screen makes.
     */
    val messageSearch: com.personalos.app.data.MessageSearchRepository =
        com.personalos.app.data
            .MessageSearchRepository(database.messageSearchDao())

    /**
     * Messages conversations: the materialised per-sender rows (v20). One owner
     * of the grouped read; the Messages screen and its thread both read through
     * it.
     */
    val conversations: com.personalos.app.data.ConversationRepository =
        com.personalos.app.data
            .ConversationRepository(database.conversationDao())

    /**
     * Resolves an SMS sender to a contact's display name. Empty until
     * [refreshContactNames] runs with READ_CONTACTS granted, so a row falls back
     * to the raw sender and nothing regresses without the permission.
     */
    private val androidContacts =
        com.personalos.app.data.contacts
            .AndroidContactNames(context)

    val contactNames: com.personalos.app.data.contacts.ContactNames = androidContacts

    /** Re-reads the address book into [contactNames]; a no-op without the grant. */
    suspend fun refreshContactNames() = androidContacts.refresh()

    /**
     * The one categorisation taxonomy: the bundled asset seeds `categories` and
     * `category_rules` once ([categorySeeder]), and [categoryStore] reads those
     * tables back as the pure core taxonomy. The DB is the runtime store; the
     * asset is only the seed.
     */
    val categorySeeder: CategorySeeder =
        CategorySeeder(
            database.categoryDao(),
            cache,
            openAsset = { context.applicationContext.assets.open(CATEGORY_TAXONOMY_ASSET) },
        )

    val categoryStore: CategoryStore = CategoryStore(database.categoryDao(), categorySeeder)

    /**
     * Rewrites SMS rows written under the old classifier to the new category
     * ids, once (version-gated). Runs from [com.personalos.app.data.work.BackfillWorker].
     * The gate folds in the applied bundle version, so a release that only edits
     * a rule still re-derives history.
     */
    val categoryBackfill: CategoryBackfill =
        CategoryBackfill(
            database.categoryBackfillDao(),
            cache,
            categorizer = { categoryStore.categorizer() },
            isContact = { contactNames.nameFor(it) != null },
            bundleVersion = { cache.read(CategorySeeder.KEY)?.value },
        )

    /**
     * The Watchers feed: fired matches with what matched. Reads through
     * [ruleWriter], so a fire's clause evaluation uses the same reader ingest did.
     */
    val ruleFires: com.personalos.app.data.RuleFireRepository =
        com.personalos.app.data.RuleFireRepository(
            database.ruleDao(),
            database.ruleFireDao(),
            ruleWriter,
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
            syncRecorder,
            categorizer = { categoryStore.categorizer() },
            isContact = { contactNames.nameFor(it) != null },
            groups =
                com.personalos.app.data.sms
                    .GroupStore(context.applicationContext),
        )

    /**
     * Real SMS sending behind the [SmsSender] seam. The compose box calls this.
     * It is wrapped so a successful send is recorded through [smsSource] — the
     * same store the inbox is written through (one fact, one owner), so the
     * thread shows its own outgoing side without any screen remembering to.
     */
    val smsSender: SmsSender =
        com.personalos.app.data
            .RecordingSmsSender(AndroidSmsSender(context.applicationContext), smsSource)

    /**
     * The send picker's address book. Reads the device contacts behind the
     * [ContactDirectory] seam; an empty list (never a throw) when READ_CONTACTS
     * is not granted.
     */
    val contacts: ContactDirectory = AndroidContactDirectory(context.applicationContext)

    /** Active SIMs for the send-SIM picker; empty when READ_PHONE_STATE is denied. */
    val sims: com.personalos.app.core.sms.SimDirectory =
        com.personalos.app.data.sims
            .AndroidSimDirectory(context.applicationContext)

    /** The shared default SIM choice (one preference, read by every send surface). */
    val simPreference: com.personalos.app.data.sims.SimPreference =
        com.personalos.app.data.sims
            .SimPreference(context.applicationContext)

    /** The opt-out group id on multi-recipient SMS (prefs; no schema change). */
    val groupStore: com.personalos.app.data.sms.GroupStore =
        com.personalos.app.data.sms
            .GroupStore(context.applicationContext)

    /** Creates a new contact in the device address book (WRITE_CONTACTS). */
    val contactWriter: com.personalos.app.core.sms.ContactWriter =
        com.personalos.app.data.contacts
            .AndroidContactWriter(context.applicationContext)

    /**
     * Numbers from attended calls (call log), behind the [com.personalos.app.core.sms.RecentCallers]
     * seam. Empty when READ_CALL_LOG is not granted. Feeds the picker's Unlisted tab.
     */
    val recentCallers: com.personalos.app.core.sms.RecentCallers =
        com.personalos.app.data.calls
            .AndroidRecentCallers(context.applicationContext)

    /**
     * Whether sending is currently permitted. Computed on each read, so a grant
     * made after construction is seen; [smsSender] also re-checks at send time.
     */
    val canSendSms: Boolean
        get() =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS,
            ) == PackageManager.PERMISSION_GRANTED

    /** Backfills tags for items that predate the active tagger (docs §11.5). */
    val retagger: Retagger =
        Retagger(
            database.retagDao(),
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
     * Decides which family re-tags next: weighted fairness over the one model, so a
     * small family (SMS) is not starved by the feed backlog. Runs as one bounded job.
     */
    val retagDispatcher: com.personalos.app.data.work.RetagDispatcher =
        com.personalos.app.data.work.RetagDispatcher(
            families =
                com.personalos.app.data.work.RetagFamilies.ALL.map {
                    com.personalos.app.core.tag
                        .FamilyState(
                            it,
                            com.personalos.app.data.work.RetagFamilies
                                .weight(it),
                        )
                },
            policy =
                com.personalos.app.core.tag
                    .DeficitRoundRobin(),
            store =
                com.personalos.app.data.work
                    .PrefsScheduleStore(context),
            runFamily = { retagger.run(it) },
            health =
                com.personalos.app.data.work
                    .PrefsHealthStore(context),
        )

    /** Tasks the standalone foreground service can run by id. Add one = add here. */
    val backgroundTasks: com.personalos.app.core.work.BackgroundTaskRegistry =
        com.personalos.app.core.work.BackgroundTaskRegistry(
            listOf(
                com.personalos.app.data.work
                    .RetagBackgroundTask(retagDispatcher),
            ),
        )

    /**
     * Ranks content by place. Temporary rung: no gazetteer or home places exist
     * yet, so this reports a single tier rather than a ranking it cannot compute
     * (docs §9). Swap here when places land - tiles do not change.
     */
    val placeRanker: PlaceRanker = RecencyPlaceRanker()

    /**
     * The refresh window for store-backed fetches. One instance, so the
     * weather/calendar/fx adapters share it and a body is fetched once per
     * window however many readers ask — including the providers, which use the
     * same cache and the same keys.
     */
    private val cachedBody: com.personalos.app.data.adapters.CachedBody =
        com.personalos.app.data.adapters
            .CachedBody(cache)

    /**
     * The region picker's directories: the provider owns the list, this supplies
     * the cached fetch. One instance, so an opened picker does not mean a request.
     */
    val calendarDirectory: com.personalos.app.data.CalendarDirectory =
        com.personalos.app.data
            .CalendarDirectory(cachedBody)

    /**
     * ADR 0005 T8: gauge observation writes — upsert-by-bucket, latest wins.
     * Counters never touch this writer (`IGNORE` + freeze stays theirs).
     */
    val observationWriter: ObservationWriter =
        ObservationWriter(
            database.eventDao(),
            database.itemFieldDao(),
            tagWriter,
            ruleWriter,
            com.personalos.app.data
                .RoomTransactor(database),
        )

    /** ADR 0005 T19: tiles read the store through here, never a provider list. */
    val observationRepository: ObservationRepository =
        ObservationRepository(database.eventDao(), database.itemFieldDao())

    val feeds: FeedIngestor =
        FeedIngestor(
            database.eventDao(),
            cache,
            tagWriter,
            mentionWriter,
            ruleWriter,
            loadSources = { sourceRepository.enabledSources() },
            syncRecorder = syncRecorder,
            gaugeAdapters =
                adaptersByKind(
                    listOf(
                        SmsKindAdapter(),
                        WeatherKindAdapter(observationWriter, cachedBody),
                        FxKindAdapter(observationWriter, cachedBody),
                        DeviceKindAdapter(),
                        com.personalos.app.data.adapters
                            .CalendarKindAdapter(database.calendarDao(), cachedBody),
                    ),
                ),
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

    /**
     * The model SDK: locate/fetch/verify on-device models. The hub app will be a
     * UI over this same store, so an app works with or without the hub installed.
     */
    val models: com.personalos.mlsdk.ModelStore =
        com.personalos.mlsdk.ModelStore(context)

    /**
     * The one owner of in-app notifications — process-wide, so a background
     * producer (a worker, a broadcast) reaches the same list the UI renders.
     * Every producer posts here and one host (the root) renders the result, so
     * no screen draws its own banner.
     */
    val notifications: com.personalos.app.core.notify.NotificationCenter =
        com.personalos.app.core.notify.AppNotifications

    /** The sync producer: posts/updates/dismisses a notification in [notifications]. */
    val sync: SyncStatus = SyncStatus(notifications)

    /** Fetches models through [models], reporting progress on the notification host. */
    val modelDownloader: com.personalos.app.data.ModelDownloader =
        com.personalos.app.data
            .ModelDownloader(models, notifications)

    val nowPlaying: NowPlayingProvider = SampleNowPlayingProvider()
    val pinned: PinnedProvider = SamplePinnedProvider()

    /**
     * The workflow engine, wired to the app's steps and a durable RunStore
     * (ADR 0009). Workflows are data, so this is a map + the engine; the trigger
     * is `SyncScheduler.enqueueWorkflow`. Lazy because the steps reference the
     * tagger/enricher and the database is opened on first use.
     */
    val workflowRuns: com.personalos.app.data.workflow.WorkflowRuns by lazy {
        com.personalos.app.data.workflow.WorkflowRuns(
            workflows =
                mapOf(
                    com.personalos.app.data.workflow.AppWorkflows.ENRICH_RETAG to
                        com.personalos.app.data.workflow.AppWorkflows
                            .enrichRetag(),
                ),
            steps =
                mapOf(
                    com.personalos.app.data.workflow.AppWorkflows.ENRICH to
                        com.personalos.app.data.workflow
                            .EnrichStep(enricher),
                    com.personalos.app.data.workflow.AppWorkflows.RETAG to
                        com.personalos.app.data.workflow
                            .RetagStep(retagger),
                ),
            store =
                com.personalos.app.data.workflow.WorkflowStore
                    .store(context),
            prepare = { upgradeTagger() },
        )
    }

    companion object {
        private const val PLACES_ASSET = "places.dat"

        @Volatile
        private var shared: AppContainer? = null

        /**
         * The process-wide container — one per process, not one per worker.
         *
         * Workers used to each build their own, so each carried its own `ModelStore`,
         * vocabulary and **embedder**, and a swap to the ML tagger loaded the model
         * once per worker (the "double loader"). Sharing one instance means the
         * model is loaded once no matter how many workers or screens ask for it.
         */
        fun get(context: Context): AppContainer =
            shared ?: synchronized(this) {
                shared ?: AppContainer(context.applicationContext).also { shared = it }
            }
    }
}
