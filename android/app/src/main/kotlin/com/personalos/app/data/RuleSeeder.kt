package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.rules.ActionJson
import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.tag.Ulid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One bundled rule before it becomes a row: a name, its condition and its
 * action. Kept apart from [RuleEntity] so validation has something to run over
 * before an id or a timestamp exists.
 */
internal data class RuleSeed(
    val name: String,
    val conditionJson: String,
    val actionJson: String,
)

/**
 * Parses a seed's condition and action into a row, or throws.
 *
 * This is the seed-time validation: a malformed document — a mistyped key, an
 * unknown delivery, an unusable regex — fails the whole seed (which returns 0
 * and writes nothing) rather than shipping a rule that silently never matches
 * (P7). `position` is read from the parsed action, so the entity column and
 * `action_json` cannot disagree.
 *
 * @throws IllegalArgumentException when either document is malformed, has an
 * unknown key, or carries an invalid value.
 */
internal fun RuleSeed.toEntity(now: Long): RuleEntity {
    ConditionJson.parse(conditionJson)
    val action = ActionJson.parse(actionJson)
    return RuleEntity(
        id = Ulid.next(),
        name = name,
        enabled = true,
        seeded = true,
        conditionJson = conditionJson,
        actionJson = actionJson,
        position = action.position,
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * Seeds the `rules` table with the day-one set (ADR 0003 §6, §9) on first
 * launch. Mirrors [SourceSeeder] exactly:
 *
 * Runs once: when the table is non-empty this is a single `COUNT(*)` and
 * nothing else. Inserts are `IGNORE`, so an interrupted seed resumes rather
 * than duplicating. Every seed is validated before insert, so a bad seed fails
 * closed (0 rows) rather than shipping a rule the engine cannot match. Any
 * failure returns 0 — nothing in ingest depends on these rows, and a bad seed
 * must never crash launch.
 *
 * ## The rule these seeds may not break: no `field`, no series
 *
 * Typed `fields` have no storage column yet, so `RuleItem.fields` is always
 * empty (slice 4 changes that; today it is a fact). A seed using `field` — the
 * mockups' `amount > 10000` — would evaluate to `false` forever and read as a
 * broken engine, not an empty result. Series predicates (`crossing`, `delta`,
 * `min`/`max`) have the same problem in reverse: no monitor evaluates them yet
 * (slice 5). So the seeds use **only** predicates the store can supply today:
 * `subject`, `nature`, `marker`, `mention`, `source` and `text`. If you are
 * tempted to "flesh out" a seed with a field rule, this is why you must not.
 *
 * ## Real source ids only
 *
 * `source` matches `events.source` exactly. Those values are real
 * (`rss:<catalog id>` from [com.personalos.app.core.feed.FeedCatalog], written
 * by `FeedIngestor`), never the mockups' illustrative `SMS:BANK`, which is not
 * a source this app has.
 */
class RuleSeeder(
    private val dao: RuleDao,
) {
    suspend fun seed(now: Long = System.currentTimeMillis()): Int =
        // Owns its dispatcher (see Retagger.run).
        withContext(Dispatchers.IO) {
            runCatching {
                if (dao.count() > 0) return@runCatching 0
                val rows = BUNDLED.map { it.toEntity(now) }
                dao.insertAll(rows)
                Log.i(TAG, "seeded ${rows.size} rules")
                rows.size
            }.onFailure { Log.w(TAG, "rules seed failed", it) }
                .getOrDefault(0)
        }

    private companion object {
        const val TAG = "Rules"

        /**
         * Four rules, one per mockup intent, weighted by `position` (10 = most
         * prominent). The place watch deliberately has `delivery = none`: it
         * surfaces a match without interrupting (ADR §9), which is the case
         * that proves delivery and surfacing are separate axes.
         */
        val BUNDLED: List<RuleSeed> =
            listOf(
                RuleSeed(
                    name = "Finance and rates watch",
                    // The finance desks that actually carry rates news: Mint
                    // Markets and Mint Money both declare the `finance` tag.
                    conditionJson =
                        """{"all":[{"subject":"finance"},{"any":[{"source":"rss:mint-markets"},{"source":"rss:mint-money"}]}]}""",
                    actionJson = """{"delivery":"push","position":10}""",
                ),
                RuleSeed(
                    name = "Bandh and strike watch",
                    conditionJson = """{"text":{"pattern":"bandh|strike","target":"any"}}""",
                    actionJson = """{"delivery":"push","position":20}""",
                ),
                RuleSeed(
                    name = "Kolkata place watch",
                    conditionJson = """{"mention":{"kind":"place","value":"Kolkata"}}""",
                    // `none` on purpose: surface, never interrupt (ADR §9).
                    actionJson = """{"delivery":"none","position":30}""",
                ),
                RuleSeed(
                    name = "News incident watch",
                    // `marker` scopes this to editorial news. Status-page
                    // outages are tagged `incident` but deliberately *not*
                    // `news` (FeedCatalog), so they stay out of this alert.
                    conditionJson = """{"all":[{"marker":true},{"nature":"incident"}]}""",
                    actionJson = """{"delivery":"push","position":40}""",
                ),
            )
    }
}
