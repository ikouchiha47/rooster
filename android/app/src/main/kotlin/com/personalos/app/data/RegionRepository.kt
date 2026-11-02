package com.personalos.app.data

import com.personalos.app.core.map.MapCamera
import com.personalos.app.core.map.SavedRegion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The single owner of saved map cameras and per-consumer defaults. Weather,
 * Climate and later surfaces all read this store; none keeps its own copy.
 */
class RegionRepository(
    private val dao: RegionDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<SavedRegion>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    fun observeDefault(consumer: String): Flow<SavedRegion?> = dao.observeDefault(consumer).map { it?.toModel() }

    suspend fun default(consumer: String): SavedRegion? = dao.defaultRegion(consumer)?.toModel()

    suspend fun save(
        name: String,
        camera: MapCamera,
        sourceKind: String? = null,
        sourceId: String? = null,
    ): SavedRegion {
        val normalised = camera.normalised()
        val existing =
            if (sourceKind != null && sourceId != null) {
                dao.bySource(sourceKind, sourceId)
            } else {
                null
            }
        val row =
            SavedRegionEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.trim().ifEmpty { "Saved view" },
                lat = normalised.lat,
                lon = normalised.lon,
                zoom = normalised.zoom,
                bearing = normalised.bearing,
                pitch = normalised.pitch,
                sourceKind = sourceKind,
                sourceId = sourceId,
                createdAt = existing?.createdAt ?: now(),
            )
        dao.put(row)
        return row.toModel()
    }

    suspend fun delete(id: String) {
        dao.clearDefaultsFor(id)
        dao.delete(id)
    }

    suspend fun setDefault(
        consumer: String,
        regionId: String?,
    ) {
        if (regionId == null) {
            dao.clearDefault(consumer)
        } else {
            dao.putDefault(RegionDefaultEntity(consumer, regionId))
        }
    }

    private fun SavedRegionEntity.toModel(): SavedRegion =
        SavedRegion(
            id = id,
            name = name,
            camera = MapCamera(lat, lon, zoom, bearing, pitch).normalised(),
            sourceKind = sourceKind,
            sourceId = sourceId,
            createdAt = createdAt,
        )
}
