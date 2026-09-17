package com.personalos.app.core.rules

/**
 * Short names for the two SMS-supplied `item_fields` (ADR 0003 §3, §6, §13).
 *
 * ADR 0005 retires the closed [SUPPLIED] set as the declaration of what
 * exists: the facet catalog
 * ([com.personalos.app.core.catalog.CatalogSeeds]) owns that now, and
 * [com.personalos.app.data.RuleSeeder]'s guard test enforces it. Producers
 * keep referencing these constants rather than spelling the strings.
 */
object FieldNames {
    /** The sender address a message source already has (`SmsSource` today). */
    const val SENDER = "sender"

    /** A transaction amount parsed from a message body (`SmsSource` today). */
    const val AMOUNT = "amount"

    /** The SMS-supplied names; the catalog owns the full declaration. */
    val SUPPLIED: Set<String> = setOf(SENDER, AMOUNT)
}
