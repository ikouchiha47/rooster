package com.personalos.app.core.calendar

/**
 * One choosable region from a provider's directory: a country, or a subdivision
 * of one.
 *
 * [slug] is the **identity** and is what a source row stores (`india/west-bengal`);
 * [name] is only ever for display. Keeping the two apart is what lets the picker
 * show "West Bengal" without changing what is persisted.
 */
data class Region(
    val slug: String,
    val name: String,
    val subdivision: Boolean,
)

/**
 * Reads a region directory out of a provider's own pages — pure, no
 * `android.*`, so the shapes are pinned by tests rather than by clicking.
 *
 * This exists because asking a person to type `india/west-bengal` is a memory
 * test: the provider already publishes the choices, and these parsers are how
 * they reach a picker.
 */
object CalendarDirectoryParser {
    /**
     * Country links on the all-countries page.
     *
     * The flag `<i class="sprite sprite-XX">` is **required**, not optional: it
     * is what separates a real country row from the page's own navigation
     * (`/countries/by-continent` has no sprite). It also sits between the href
     * and the name, which is why a naive "name after href" match finds nothing.
     */
    private val COUNTRY = Regex("""/countries/([a-z0-9\-]+)"[^>]*><i[^>]*></i>(?:&nbsp;)*([^<]{2,60})</a>""")

    /**
     * A country page's holiday links carry the region path:
     * `/holidays/india/west-bengal/poila-boishakh`. Two path segments after the
     * country means a subdivision; one means country-level, which is not a region.
     */
    private fun subdivision(country: String) = Regex("""/holidays/${Regex.escape(country)}/([a-z0-9\-]+)/[a-z0-9\-]+""")

    fun parseCountries(html: String): List<Region> {
        if (html.isBlank()) return emptyList()
        return COUNTRY
            .findAll(html)
            .mapNotNull { match ->
                val slug = match.groupValues[1]
                val name = match.groupValues[2].trim()
                if (slug.isBlank() || name.isBlank()) return@mapNotNull null
                Region(slug = slug, name = name, subdivision = false)
            }.distinctBy { it.slug }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    fun parseSubdivisions(
        html: String,
        country: String,
    ): List<Region> {
        if (html.isBlank() || country.isBlank()) return emptyList()
        return subdivision(country)
            .findAll(html)
            .mapNotNull { it.groupValues[1].takeIf { slug -> slug.isNotBlank() } }
            .distinct()
            .map { Region(slug = "$country/$it", name = displayName(it), subdivision = true) }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    /**
     * `west-bengal` → `West Bengal`, `india/west-bengal` → `India / West Bengal`.
     * Deliberately derived from the slug, never from a page's `title` attribute,
     * which on these pages lists the states a holiday applies to.
     */
    fun displayName(slug: String): String =
        slug
            .split('/')
            .joinToString(" / ") { part ->
                part.split('-').joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercaseChar() }
                }
            }
}
