package com.personalos.app.core.tag

/**
 * Canonical tag names. Multi-valued by design: a flood is `news` + `weather`
 * + `travel` at once. See docs/ARCHITECTURE.md §10.5.
 */
object Tags {
    const val NEWS = "news"
    const val WEATHER = "weather"
    const val FINANCE = "finance"
    const val TECH = "tech"
    const val EXPENSE = "expense"
    const val TRAVEL = "travel"
    const val FESTIVAL = "festival"
    const val GAMES = "games"
    const val PROMO = "promo"
    const val INCIDENT = "incident"
    const val PAPER = "paper"
    const val PERSONAL = "personal"
    const val OFFICIAL = "official"

    val ALL: Set<String> =
        setOf(
            NEWS,
            WEATHER,
            FINANCE,
            TECH,
            EXPENSE,
            TRAVEL,
            FESTIVAL,
            GAMES,
            PROMO,
            INCIDENT,
            PAPER,
            PERSONAL,
            OFFICIAL,
        )
}

/** Strategy family of a tagger; stored alongside its version (docs §11.3). */
enum class TaggerKind {
    REGEX,
    HEURISTIC,
    ML,
    CASCADE,
    LLM,
    ;

    /**
     * Capability order, used to choose which tagger is "current". A stronger rung
     * must never lose the flag to a weaker one: a sync worker that only has the
     * heuristic must not demote the cascade a backfill activated, or
     * `item_tags_current` would report the weaker tags.
     */
    val rank: Int
        get() =
            when (this) {
                REGEX, HEURISTIC -> 0
                ML -> 1
                CASCADE -> 2
                LLM -> 3
            }
}

data class TagInput(
    val text: String,
    val source: Transport,
    val sender: String? = null,
    val language: String? = null,
    /**
     * Every tag the *source* declares for its items: a news feed declares
     * `news`, a travel desk `news` + `travel`, a status page `incident` alone.
     * The tagger takes these as given rather than inferring from [source] -
     * inferring "RSS means news" is what put a status page's outages under news.
     */
    val declaredTags: Set<String> = emptySet(),
)

data class TagResult(
    val tags: Set<String>,
    val entities: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val confidence: Float = 1f,
    val taggerId: String = "",
)

/**
 * Multi-label tagger. Swapped in `AppContainer`; tiles never know which
 * implementation is running. See the strategy ladder in docs/ARCHITECTURE.md §10.2.
 */
interface Tagger {
    /** Stable id, `"<kind>-v<version>"` - stored on every tag row. */
    val id: String

    val kind: TaggerKind

    val version: Int

    suspend fun tag(input: TagInput): TagResult

    /**
     * Tags many items at once. The default is one-by-one; an ML tagger overrides
     * it to send a whole chunk through the model in a single run (batched
     * inference), which is where an embedding runtime earns its keep. Same
     * results as calling [tag] per item; only the cost changes.
     */
    suspend fun tagAll(inputs: List<TagInput>): List<TagResult> = inputs.map { tag(it) }
}
