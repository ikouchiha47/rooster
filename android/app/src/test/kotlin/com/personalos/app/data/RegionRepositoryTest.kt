package com.personalos.app.data

import com.personalos.app.core.map.MapCamera
import com.personalos.app.core.map.RegionConsumers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionRepositoryTest {
    private val dao = FakeRegionDao()
    private val repo = RegionRepository(dao, now = { 42L })

    @Test
    fun `saving the same source updates the camera instead of duplicating`() =
        runBlocking {
            val first = repo.save("Home", MapCamera(10.0, 20.0, 4.0), "weather_place", "p1")
            val second = repo.save("Home", MapCamera(11.0, 21.0, 5.0), "weather_place", "p1")

            assertEquals(first.id, second.id)
            assertEquals(1, dao.all().size)
            assertEquals(11.0, second.camera.lat, 0.0001)
        }

    @Test
    fun `a consumer default resolves to the saved region`() =
        runBlocking {
            val saved = repo.save("Bengal", MapCamera(22.0, 88.0, 5.0))
            repo.setDefault(RegionConsumers.CLIMATE, saved.id)

            assertEquals(saved.id, repo.observeDefault(RegionConsumers.CLIMATE).first()?.id)
            assertEquals(saved.id, repo.default(RegionConsumers.CLIMATE)?.id)
        }

    @Test
    fun `deleting a region clears defaults that pointed at it`() =
        runBlocking {
            val saved = repo.save("Trip", MapCamera(1.0, 2.0, 3.0))
            repo.setDefault(RegionConsumers.HOME, saved.id)

            repo.delete(saved.id)

            assertNull(repo.observeDefault(RegionConsumers.HOME).first())
        }

    private class FakeRegionDao : RegionDao {
        private val regions = MutableStateFlow<List<SavedRegionEntity>>(emptyList())
        private val defaults = MutableStateFlow<Map<String, String?>>(emptyMap())

        override fun observeAll(): Flow<List<SavedRegionEntity>> = regions.map { rows -> rows.sortedBy { it.name.lowercase() } }

        override suspend fun all(): List<SavedRegionEntity> = regions.value.sortedBy { it.name.lowercase() }

        override suspend fun byId(id: String): SavedRegionEntity? = regions.value.firstOrNull { it.id == id }

        override suspend fun bySource(
            kind: String,
            sourceId: String,
        ): SavedRegionEntity? = regions.value.firstOrNull { it.sourceKind == kind && it.sourceId == sourceId }

        override suspend fun put(region: SavedRegionEntity) {
            regions.value = regions.value.filterNot { it.id == region.id } + region
        }

        override suspend fun delete(id: String) {
            regions.value = regions.value.filterNot { it.id == id }
        }

        override suspend fun clearDefaultsFor(id: String) {
            defaults.value = defaults.value.mapValues { (_, value) -> value.takeUnless { it == id } }
        }

        override suspend fun putDefault(row: RegionDefaultEntity) {
            defaults.value = defaults.value + (row.consumer to row.regionId)
        }

        override suspend fun clearDefault(consumer: String) {
            defaults.value = defaults.value - consumer
        }

        override fun observeDefaultRow(consumer: String): Flow<RegionDefaultEntity?> = defaults.map { map -> map[consumer]?.let { RegionDefaultEntity(consumer, it) } }

        override fun observeDefault(consumer: String): Flow<SavedRegionEntity?> =
            combine(regions, defaults) { rows, map ->
                map[consumer]?.let { id -> rows.firstOrNull { it.id == id } }
            }

        override suspend fun defaultRegion(consumer: String): SavedRegionEntity? = defaults.value[consumer]?.let { id -> regions.value.firstOrNull { it.id == id } }
    }
}
