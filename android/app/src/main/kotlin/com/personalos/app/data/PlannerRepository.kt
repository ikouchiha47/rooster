package com.personalos.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A planner item with its tag ids; labels/colors come from the tag table. */
data class PlannerItemWithTags(
    val item: PlannerItemEntity,
    val tagIds: List<String>,
)

/**
 * The single owner of user-owned planner facts: workspaces, events/reminders,
 * and their tags. Source items are untouched; an item may point at one by id.
 */
class PlannerRepository(
    private val planner: PlannerDao,
    private val tagDao: PlannerTagDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val workspaces: Flow<List<PlannerWorkspaceEntity>> = planner.observeWorkspaces()
    val tags: Flow<List<PlannerTagEntity>> = tagDao.observeTags()

    suspend fun defaultWorkspace(): PlannerWorkspaceEntity =
        planner.defaultWorkspace()
            ?: PlannerWorkspaceEntity(
                id = PERSONAL_ID,
                name = PERSONAL_NAME,
                kind = "personal",
                isDefault = true,
                sourceId = null,
                createdAt = now(),
            )

    fun itemsInRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<List<PlannerItemWithTags>> = planner.observeRange(fromMs, toMs).map(::withTags)

    /** All planner items, for recurrence expansion over a visible window. */
    fun allItems(): Flow<List<PlannerItemWithTags>> = planner.observeAll().map(::withTags)

    private suspend fun withTags(items: List<PlannerItemEntity>): List<PlannerItemWithTags> {
        val byId = tagDao.tagsForItems(items.map { it.id }).groupBy { it.itemId }
        return items.map { item -> PlannerItemWithTags(item, byId[item.id].orEmpty().map { it.tagId }) }
    }

    suspend fun item(id: String): PlannerItemWithTags? =
        planner.item(id)?.let { item ->
            PlannerItemWithTags(item, tagDao.tagsForItem(id).map { it.tagId })
        }

    suspend fun save(
        item: PlannerItemEntity,
        tagIds: Collection<String>,
    ) {
        planner.putItem(item.copy(updatedAt = now()))
        tagDao.clearItemTags(item.id)
        if (tagIds.isNotEmpty()) {
            tagDao.putItemTags(tagIds.distinct().map { PlannerItemTagEntity(item.id, it) })
        }
    }

    suspend fun setStatus(
        itemId: String,
        status: String,
    ) {
        planner.setStatus(itemId, status, now())
    }

    suspend fun delete(itemId: String) {
        tagDao.clearItemTags(itemId)
        planner.deleteItem(itemId)
    }

    suspend fun setDefaultWorkspace(workspaceId: String) {
        planner.clearDefaultWorkspaces()
        planner.markDefaultWorkspace(workspaceId)
    }

    private companion object {
        const val PERSONAL_ID = "personal"
        const val PERSONAL_NAME = "Personal"
    }
}
