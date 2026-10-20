package com.personalos.app.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads owned by a tile's content tabs that are narrower than the general
 * event queries. A tile can ask for a tag *and* a source without putting a
 * screen-specific predicate on [EventDao], whose contract stays the shared
 * timeline.
 */
@Dao
interface TileDao {
    /** One source's items carrying a tag, keyset-paged. */
    @Query(Sql.EVENTS_BY_TAG_SOURCE_PAGE)
    suspend fun pageByTagSource(
        tag: String,
        source: String,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<TaggedEvent>

    /** Count for the same read, so a tab badge cannot disagree with its list. */
    @Query(Sql.EVENTS_COUNT_BY_TAG_SOURCE)
    fun observeCountByTagSource(
        tag: String,
        source: String,
    ): Flow<Int>

    /** The families a source filter can offer under a tag, with their counts. */
    @Query(Sql.EVENTS_SOURCE_FAMILIES_BY_TAG)
    fun observeSourceFamilies(
        tag: String,
    ): Flow<List<SourceCount>>

    /** Families that can appear in a feed tab; excludes observations and SMS. */
    @Query(Sql.EVENTS_FEED_SOURCE_FAMILIES_BY_TAG)
    fun observeFeedSourceFamilies(
        tag: String,
    ): Flow<List<SourceCount>>

    /** Feed rows for a tag, optionally narrowed to one source family. */
    @Query(Sql.EVENTS_BY_TAG_PAGE_ITEMS_SCOPED)
    suspend fun pageByTagItemsScoped(
        tag: String,
        prefix: String?,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<TaggedEvent>

    /** Count of the same scoped feed rows. */
    @Query(Sql.EVENTS_COUNT_BY_TAG_SCOPED)
    fun observeCountByTagScoped(
        tag: String,
        prefix: String?,
    ): Flow<Int>

    /** Day headers for a scoped tag read. */
    @Query(Sql.EVENTS_DAY_HEADERS_BY_TAG_SCOPED)
    suspend fun dayHeadersByTagScoped(
        tag: String,
        prefix: String?,
    ): List<DayHeader>

    /** One day's keyset page for a scoped tag read. */
    @Query(Sql.EVENTS_BY_TAG_DAY_PAGE_SCOPED)
    suspend fun pageByTagDayScoped(
        tag: String,
        dayStart: Long,
        dayEnd: Long,
        prefix: String?,
        cursorTs: Long?,
        cursorId: Long,
        limit: Int,
    ): List<TaggedEvent>
}
