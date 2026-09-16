package com.personalos.app.core.mention

import com.personalos.app.data.PlaceEntity

/**
 * In-memory gazetteer index over [PlaceEntity] rows.
 *
 * Pure: built from a plain list, so the data layer loads and this only
 * matches — it never touches a DAO (layers stay one-way).
 *
 * Matching rules:
 *  - surfaces are the display name, the ASCII name, and each `|`-separated
 *    alternate; anything shorter than 3 characters is dropped (it is an
 *    initial or a fragment, not a name anyone writes);
 *  - [STOPWORDS] drops bare English words that collide with an ASCII name
 *    (`Punch` is a town in J&K and a verb everywhere else);
 *  - on a surface claimed by several rows the most populous wins, so the
 *    answer is deterministic regardless of row order.
 */
class PlaceIndex(
    places: List<PlaceEntity>,
) {
    /** A raw span hit, before cross-kind arbitration. */
    data class Hit(
        val surface: String,
        val entityId: String,
        val canonical: Boolean,
        val start: Int,
        val end: Int,
    )

    private val byLower: Map<String, Entry>
    private val pattern: Regex?

    init {
        val merged = LinkedHashMap<String, Entry>()
        for (place in places) {
            val surfaces = LinkedHashMap<String, Boolean>()
            surfaces[place.name] = true
            surfaces[place.ascii] = true
            for (alternate in place.alternates.split('|')) {
                surfaces.putIfAbsent(alternate, false)
            }
            for ((surface, canonical) in surfaces) {
                val trimmed = surface.trim()
                if (trimmed.length < MIN_SURFACE_LENGTH) continue
                if (trimmed.lowercase() in STOPWORDS) continue
                val key = trimmed.lowercase()
                val existing = merged[key]
                if (existing == null || place.population > existing.population) {
                    merged[key] = Entry(trimmed, place.id, canonical || existing?.canonical == true, place.population)
                }
            }
        }
        byLower = merged
        pattern =
            wordPattern(
                merged.keys.sortedByDescending { it.length }.map { Regex.escape(it) },
            )
    }

    /** Every place span in [text], longest match winning at each position. */
    fun find(text: String): List<Hit> {
        val regex = pattern ?: return emptyList()
        return regex
            .findAll(text)
            .mapNotNull { match ->
                val entry = byLower[match.value.lowercase()] ?: return@mapNotNull null
                Hit(
                    surface = entry.surface,
                    entityId = entry.id,
                    canonical = entry.canonical,
                    start = match.range.first,
                    end = match.range.last + 1,
                )
            }.toList()
    }

    fun isEmpty(): Boolean = byLower.isEmpty()

    private data class Entry(
        val surface: String,
        val id: String,
        val canonical: Boolean,
        val population: Long,
    )

    companion object {
        const val MIN_SURFACE_LENGTH = 3

        /**
         * Gazetteer ASCII names that are also ordinary English words. A bare
         * occurrence in text is near-certainly the word, not the town — tuned
         * by test (`Punch` failed first). Multi-word surfaces containing these
         * are unaffected; only the exact single-word surface is dropped.
         *
         * Each addition below fired on device before it was listed: `along`
         * on a gold-prices body ("along with"), `men` on a sports body
         * ("men's team"), `ali`/`kant`/`patra` on surnames, `met` on the verb.
         * All are towns of 2k–27k people; the collision cost dwarfs the match
         * value at 0.85 canonical confidence.
         */
        val STOPWORDS: Set<String> = setOf("bank", "punch", "along", "men", "ali", "kant", "patra", "met")
    }
}
