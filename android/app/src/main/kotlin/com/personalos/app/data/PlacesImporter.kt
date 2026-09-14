package com.personalos.app.data

import java.io.InputStream

/**
 * Parses the bundled gazetteer asset into [PlaceEntity] rows.
 *
 * The asset (`places.dat`) is gzip-compressed content under a `.dat` name —
 * aapt decompresses and renames anything ending in `.gz`, so the name is load
 * bearing. Open it with a [java.util.zip.GZIPInputStream], never rename it.
 *
 * Column layout is defined by `tools/build_places.py` (verified, not assumed):
 * id, name, ascii, lat, lon, fclass, fcode, admin1, admin2, admin3,
 * population, pipe-joined alternates. Only admin1 is stored.
 *
 * Malformed rows (wrong column count, blank id/name, unparsable numbers) are
 * skipped, never thrown: one bad GeoNames line must not sink the whole seed.
 */
object PlacesImporter {
    const val COLUMN_COUNT = 12

    private const val ID = 0
    private const val NAME = 1
    private const val ASCII = 2
    private const val LAT = 3
    private const val LON = 4
    private const val FCLASS = 5
    private const val FCODE = 6
    private const val ADMIN1 = 7
    private const val POPULATION = 10
    private const val ALTERNATES = 11

    fun parseLines(lines: Sequence<String>): List<PlaceEntity> = lines.mapNotNull { parseRow(it) }.toList()

    fun load(gunzipped: InputStream): List<PlaceEntity> = gunzipped.bufferedReader(Charsets.UTF_8).useLines { parseLines(it) }

    private fun parseRow(line: String): PlaceEntity? {
        // \t split, not a CSV parser: the builder joins with tabs and GeoNames
        // names never contain them.
        val fields = line.split('\t')
        if (fields.size < COLUMN_COUNT) return null
        if (fields[ID].isBlank() || fields[NAME].isBlank()) return null
        val lat = fields[LAT].toDoubleOrNull() ?: return null
        val lon = fields[LON].toDoubleOrNull() ?: return null
        val population = fields[POPULATION].toLongOrNull() ?: return null
        return PlaceEntity(
            id = fields[ID],
            name = fields[NAME],
            ascii = fields[ASCII],
            lat = lat,
            lon = lon,
            fclass = fields[FCLASS],
            fcode = fields[FCODE],
            admin1 = fields[ADMIN1],
            population = population,
            alternates = fields[ALTERNATES],
        )
    }
}
