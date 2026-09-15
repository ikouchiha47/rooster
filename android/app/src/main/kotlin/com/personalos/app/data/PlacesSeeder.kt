package com.personalos.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Seeds the `places` table from the bundled gazetteer on first launch.
 *
 * Runs once: when the table is non-empty this is a single `COUNT(*)` and
 * nothing else. Inserts are `IGNORE`, so an interrupted seed resumes rather
 * than duplicating. Any failure leaves an empty table and returns 0 — search
 * degrades to no places, it never crashes launch.
 */
class PlacesSeeder(
    private val dao: PlaceDao,
    private val openAsset: () -> InputStream,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    suspend fun seed(): Int =
        // Owns its dispatcher (see Retagger.run).
        withContext(Dispatchers.IO) {
            if (dao.count() > 0) return@withContext 0
            val places =
                runCatching {
                    openAsset().use { PlacesImporter.load(GZIPInputStream(it)) }
                }.onFailure { Log.w(TAG, "places seed failed", it) }
                    .getOrNull()
                    .orEmpty()
            if (places.isEmpty()) return@withContext 0

            var seeded = 0
            for (batch in places.chunked(batchSize)) {
                runCatching { dao.insertAll(batch) }
                    .onFailure { Log.w(TAG, "places seed insert failed", it) }
                    .onSuccess { seeded += batch.size }
            }
            Log.i(TAG, "seeded $seeded places")
            seeded
        }

    private companion object {
        const val TAG = "Places"
        const val DEFAULT_BATCH_SIZE = 500
    }
}
