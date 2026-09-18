package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    // ---------------------------------------------------------------- counts
    // Aggregates only. Never load a table to compute a number.

    @Query(Sql.EVENTS_COUNT)
    fun observeCount(): Flow<Int>

    @Query(Sql.EVENTS_COUNT_SINCE_TYPE)
    fun observeCountSince(
        type: String,
        since: Long,
    ): Flow<Int>

    @Query(Sql.EVENTS_DISTINCT_SOURCES)
    fun observeSourceCount(): Flow<Int>

    @Query(Sql.EVENTS_COUNT_SINCE)
    fun observeCountAllSince(since: Long): Flow<Int>

    @Query(Sql.EVENTS_DISTINCT_FEED_SOURCES)
    fun observeFeedSourceCount(): Flow<Int>

    /** Count per screen tab mode: 'all' | 'inbox' | 'sent' | 'other'. */
    @Query(Sql.EVENTS_COUNT_BY_MODE)
    fun observeCountByMode(mode: String): Flow<Int>

    @Query(Sql.EVENTS_COUNT)
    suspend fun getCount(): Int

    /** Changes whenever a new event is written; used to refresh a paged list. */
    @Query(Sql.EVENTS_MAX_ID)
    fun observeMaxId(): Flow<Long?>

    // ------------------------------------------------------- keyset paging
    // Cursor pagination (timestamp, id), never LIMIT/OFFSET: offset paging
    // degrades and skips or duplicates rows while new SMS arrive.

    @Query(Sql.EVENTS_PAGE_BY_MODE)
    suspend fun page(
        mode: String,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<EventEntity>

    @Query(Sql.EVENTS_DELETE_ALL)
    suspend fun deleteAll()

    // ------------------------------------------------------------- radar feed
    // The unified stream, filtered by event category (SmsClass name for SMS).
    // Cursor paging, same as Messages.

    @Query(Sql.EVENTS_COUNT_BY_CATEGORY)
    fun observeCountByCategory(category: String?): Flow<Int>

    /** Per-source item counts, for the Sources screen. */
    @Query(Sql.EVENTS_COUNT_BY_SOURCE)
    fun observeSourceCounts(): Flow<List<SourceCount>>

    /** One batch of the re-tag walk, in insertion order. */
    @Query(Sql.EVENTS_AFTER_ID)
    suspend fun itemsAfter(
        afterId: Long,
        limit: Int,
    ): List<RetagCandidate>

    @Query(Sql.EVENTS_RADAR_PAGE)
    suspend fun radarPage(
        category: String?,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<EventEntity>

    // ----------------------------------------------------------- rule preview
    // One bounded read for the authoring dry run: date-windowed on the indexed
    // timestamp, capped, newest first, and optionally narrowed per indexed column
    // (ADR §12). The evaluator, never this query, decides a match.

    /**
     * Candidates for a rule preview. Each nullable filter is a coarse narrowing
     * on an indexed column; passing null leaves that dimension unbounded. Callers
     * ask for one more row than they intend to scan so truncation is observable
     * (see [RulePreviewLoader]).
     */
    @Query(Sql.EVENTS_RULE_PREVIEW_CANDIDATES)
    suspend fun rulePreviewCandidates(
        since: Long,
        sourceId: String?,
        tag: String?,
        mentionKind: String?,
        mentionValue: String?,
        limit: Int,
    ): List<RulePreviewCandidate>

    // ----------------------------------------------------------- tag-scoped
    // What tiles read: everything carrying a tag, keyset-paged, resolved
    // against the active tagger only (docs/ARCHITECTURE.md §11.4).

    @Query(Sql.EVENTS_BY_TAG_PAGE)
    suspend fun pageByTag(
        tag: String,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<TaggedEvent>

    /** Tag timeline without gauge observations (see [Sql.EVENTS_BY_TAG_PAGE_ITEMS]). */
    @Query(Sql.EVENTS_BY_TAG_PAGE_ITEMS)
    suspend fun pageByTagItems(
        tag: String,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<TaggedEvent>

    @Query(Sql.EVENTS_COUNT_BY_TAG)
    fun observeCountByTag(tag: String): Flow<Int>

    /**
     * Day headers for a tag, newest day first. Loads before any page: the list
     * sections come from here, and each opened day starts its own cursor.
     */
    @Query(Sql.EVENTS_DAY_HEADERS_BY_TAG)
    suspend fun dayHeadersByTag(tag: String): List<DayHeader>

    /**
     * One keyset page inside a single day window: the [pageByTag] shape plus
     * the day bounds, so days paginate independently.
     */
    @Query(Sql.EVENTS_BY_TAG_DAY_PAGE)
    suspend fun pageByTagDay(
        tag: String,
        dayStart: Long,
        dayEnd: Long,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<TaggedEvent>

    /** One item with its full tag set, for the detail screen. */
    @Query(Sql.EVENT_BY_ID)
    suspend fun eventById(id: Long): TaggedEvent?

    // ----------------------------------------------------------- enrichment

    /** Candidates for enrichment: has URL, short/blank content, never attempted. */
    @Query(Sql.ENRICHMENT_CANDIDATES)
    suspend fun enrichmentCandidates(limit: Int): List<EnrichmentCandidate>

    /** Mark an item as enrichment-attempted (success or failure). */
    @Query(Sql.ENRICHMENT_MARK_ATTEMPTED)
    suspend fun markEnrichmentAttempted(
        id: Long,
        enrichedAt: Long,
    )

    /** Update content when enrichment succeeds. */
    @Query(Sql.ENRICHMENT_UPDATE_CONTENT)
    suspend fun updateEnrichedContent(
        id: Long,
        content: String,
        enrichedAt: Long,
    )

    // ------------------------------------------------------- observations
    // ADR 0005 T8/T17: gauges upsert by bucket key, keeping the same ulid.

    @Query(Sql.EVENT_BY_DEDUPE_KEY)
    suspend fun byDedupeKey(dedupeKey: String): EventEntity?

    @Query(Sql.EVENT_UPDATE_OBSERVATION)
    suspend fun updateObservation(
        ulid: String,
        timestamp: Long,
        title: String,
        content: String,
    )

    @Query(Sql.LATEST_OBSERVATION_BY_SOURCE)
    suspend fun latestObservation(sourceId: String): EventEntity?

    @Query(Sql.LATEST_OBSERVATION_BY_SOURCE)
    fun observeLatestObservation(sourceId: String): kotlinx.coroutines.flow.Flow<EventEntity?>

    @Query(Sql.LATEST_OBSERVATIONS_BY_SOURCES)
    suspend fun latestObservations(sourceIds: List<String>): List<EventEntity>

    @Query(Sql.OBSERVATIONS_BY_PREFIX)
    suspend fun observationsByPrefix(prefix: String): List<EventEntity>
}
