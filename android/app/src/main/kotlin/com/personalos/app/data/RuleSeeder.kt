package com.personalos.app.data

import android.util.Log
import com.personalos.app.core.rules.ActionJson
import com.personalos.app.core.rules.ConditionJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One bundled rule before it becomes a row: a stable id, a name, its condition
 * and its action. Kept apart from [RuleEntity] so validation has something to
 * run over before a timestamp exists.
 *
 * The `id` is a fixed string, not a generated ULID: it is the seed's identity
 * across releases, so a run can tell "this bundled rule is already installed"
 * from "this bundled rule is new" (see [RuleSeeder]).
 */
internal data class RuleSeed(
    val id: String,
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
        id = id,
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
 * Reconciles the `rules` table with the bundled day-one set (ADR 0003 §6, §9)
 * on every launch. Mirrors [SourceSeeder] exactly:
 *
 * **Add-only, keyed by a stable id.** Each bundled seed carries a fixed `id`
 * (e.g. `seed:rule:bandh-and-strike-watch`), and a run inserts only the seeds
 * whose id is absent. A row that already exists is never updated or deleted, so
 * a user's edits and disabled state survive, and `seeded = true` stays
 * immutable. Repeated launches are therefore no-ops.
 *
 * **This is a reconcile, not a first-run gate.** Gating on `count() == 0` meant
 * a bundled seed added after a user's first launch was invisible forever — the
 * schema v12 device held four rules because of exactly that. Inserting by id
 * lets a later release's new seed reach an existing install.
 *
 * **A changed condition never silently overwrites a stored row.** Identity is
 * the id alone, never the condition. When a later release edits a bundled
 * seed's condition, the row already stored under that id keeps its old
 * document — "leave existing rows alone" — and the divergence is logged so it
 * is visible rather than silent. A bundled rule, once installed, is not
 * retro-actively rewritten by a release.
 *
 * Every missing seed is validated before the first write, so a bad seed fails
 * closed (0 rows) rather than shipping a rule the engine cannot match. Any
 * failure returns 0 — nothing in ingest depends on these rows, and a bad seed
 * must never crash launch.
 *
 * ## The rule these seeds may not break: only supplied fields, no series
 *
 * A seed using a `field` whose name no producer writes — the mockups'
 * `amount > 10000` used to be this — would evaluate to `false` forever and read
 * as a broken engine. Storage now exists (`item_fields`, schema v12) and
 * [SmsSource] is the first producer, but only for the names in
 * [com.personalos.app.core.rules.FieldNames.SUPPLIED]. A seed may name one of
 * those and no other; the guard test enforces it. Series predicates
 * (`crossing`, `delta`, `min`/`max`) have the same problem in reverse: no
 * monitor evaluates them yet (slice 5). So the seeds use **only** predicates
 * the store can supply today: `subject`, `nature`, `marker`, `mention`,
 * `source`, `text`, and a supplied `field`. If you are tempted to "flesh out" a
 * seed with a field no producer writes, this is why you must not.
 *
 * ## Real source ids only
 *
 * `source` matches `events.source` exactly. Those values are real
 * (`rss:<catalog id>` from [com.personalos.app.core.feed.FeedCatalog], written
 * by `FeedIngestor`, and [SmsSource.SOURCE_ID] for SMS), never the mockups'
 * illustrative `SMS:BANK`, which is not a source this app has.
 */
class RuleSeeder(
    private val dao: RuleDao,
) {
    suspend fun seed(now: Long = System.currentTimeMillis()): Int =
        // Owns its dispatcher (see Retagger.run).
        withContext(Dispatchers.IO) {
            runCatching {
                val stored = dao.all().associateBy { it.id }
                // Validate every missing seed before the first write: a bad
                // seed fails closed rather than writing a partial set.
                val rows = BUNDLED.filter { it.id !in stored }.map { it.toEntity(now) }
                logDrift(stored)
                if (rows.isEmpty()) return@runCatching 0
                dao.insertAll(rows)
                Log.i(TAG, "rules seed: +${rows.size}, ${stored.size} kept")
                rows.size
            }.onFailure { Log.w(TAG, "rules seed failed", it) }
                .getOrDefault(0)
        }

    /**
     * Names every bundled seed whose stored document differs from what this
     * release ships. The stored row is kept — that is the contract — so this
     * warning is the only signal that a release's edit did not land.
     */
    private fun logDrift(stored: Map<String, RuleEntity>) {
        BUNDLED.forEach { seed ->
            val row = stored[seed.id] ?: return@forEach
            if (row.seeded && (row.conditionJson != seed.conditionJson || row.actionJson != seed.actionJson)) {
                Log.w(TAG, "bundled rule '${seed.id}' changed in a later release; keeping the stored row")
            }
        }
    }

    private companion object {
        const val TAG = "Rules"

        /**
         * Five rules, one per mockup intent, weighted by `position` (10 = most
         * prominent). The place watch deliberately has `delivery = none`: it
         * surfaces a match without interrupting (ADR §9), which is the case
         * that proves delivery and surfacing are separate axes.
         *
         * The `seed:rule:` ids are stable across releases — that is what makes
         * reconciliation possible, so a rename or reorder must never change one.
         */
        val BUNDLED: List<RuleSeed> =
            listOf(
                RuleSeed(
                    id = "seed:rule:finance-and-rates-watch",
                    name = "Finance and rates watch",
                    // The finance desks that actually carry rates news: Mint
                    // Markets and Mint Money both declare the `finance` tag.
                    conditionJson =
                        """{"all":[{"subject":"finance"},{"any":[{"source":"rss:mint-markets"},{"source":"rss:mint-money"}]}]}""",
                    actionJson = """{"delivery":"push","position":10}""",
                ),
                RuleSeed(
                    id = "seed:rule:bandh-and-strike-watch",
                    name = "Bandh and strike watch",
                    conditionJson = """{"text":{"pattern":"bandh|strike","target":"any"}}""",
                    actionJson = """{"delivery":"push","position":20}""",
                ),
                RuleSeed(
                    id = "seed:rule:kolkata-place-watch",
                    name = "Kolkata place watch",
                    conditionJson = """{"mention":{"kind":"place","value":"Kolkata"}}""",
                    // `none` on purpose: surface, never interrupt (ADR §9).
                    actionJson = """{"delivery":"none","position":30}""",
                ),
                RuleSeed(
                    id = "seed:rule:news-incident-watch",
                    name = "News incident watch",
                    // `marker` scopes this to editorial news. Status-page
                    // outages are tagged `incident` but deliberately *not*
                    // `news` (FeedCatalog), so they stay out of this alert.
                    conditionJson = """{"all":[{"marker":true},{"nature":"incident"}]}""",
                    actionJson = """{"delivery":"push","position":40}""",
                ),
                RuleSeed(
                    id = "seed:rule:large-sms-amount-watch",
                    name = "Large SMS amount watch",
                    // The mockups' `amount > 10000` (design-rules/recipe.html),
                    // against the one producer that supplies an `amount` today:
                    // SmsSource's transaction parser. `source` is the real
                    // `events.source` SMS rows carry (SmsSource.SOURCE_ID), not
                    // the mockups' illustrative `SMS:BANK`. The parser emits no
                    // amount for a bill, a promo or an ambiguous message, so a
                    // `false` here is "no transaction amount over the threshold"
                    // rather than a broken engine.
                    conditionJson =
                        """{"all":[{"source":"sms"},{"field":{"name":"amount","op":"gt","value":10000}}]}""",
                    actionJson = """{"delivery":"push","position":50}""",
                ),
            )
    }
}
