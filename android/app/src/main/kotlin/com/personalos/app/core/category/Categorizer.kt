package com.personalos.app.core.category

/**
 * The machine-learning seam: consulted only when no rule matches, before the
 * Unknown category. Returns a category id, or null to abstain. An on-device
 * kNN tagger plugs in here later; nothing else changes.
 */
fun interface Fallback {
    fun classify(
        sender: String,
        body: String,
        isContact: Boolean,
    ): String?
}

/**
 * The stable categorisation strategy: load the taxonomy's rules by priority
 * (ascending; first match wins), then the [Fallback] seam, then the Unknown
 * category. The strategy is fixed; the behaviour is data — the categories and
 * rules in the taxonomy, plus the registered [RuleMatcher]s and
 * [SenderKindRule]s.
 *
 * Pure and channel-agnostic: SMS supplies DLT-suffix provenance today, an email
 * channel would supply the same shape from its sender domain. No Android types.
 */
class Categorizer(
    private val taxonomy: CategoryTaxonomy,
    matchers: List<RuleMatcher> = BundledMatchers.all,
    private val senderKindRules: List<SenderKindRule> = BundledSenderKinds.all,
    private val fallback: Fallback? = null,
) {
    private val byType: Map<String, RuleMatcher> = matchersByType(matchers)
    private val rules: List<CategoryRule> = taxonomy.rules.sortedBy { it.priority }

    /** The open provenance id of a sender (`contact`/`header`/`short_code`/`number`/`unknown`). */
    fun senderKind(
        sender: String,
        isContact: Boolean,
    ): String = senderKindRules.firstOrNull { it.matches(sender, isContact) }?.kindId ?: BundledSenderKinds.UNKNOWN

    /** The DLT-derived category, when a `header_suffix` rule names one; null for `S`/`T`/non-DLT. */
    fun dltCategory(sender: String): Category? {
        val suffix = dltSuffix(sender) ?: return null
        val rule =
            rules.firstOrNull {
                it.matcherType == HeaderSuffixMatcher.matcherType && it.pattern.equals(suffix, ignoreCase = true)
            }
        return rule?.let { taxonomy.category(it.categoryId) }
    }

    fun categorize(
        sender: String,
        body: String,
        isContact: Boolean,
    ): Category {
        val message = Message(sender, body, isContact)
        val provenance = Provenance(senderKind(sender, isContact), dltSuffix(sender))
        val rule = rules.firstOrNull { byType[it.matcherType]?.matches(it, message, provenance) == true }
        val id = rule?.categoryId ?: fallback?.classify(sender, body, isContact) ?: taxonomy.unknownId
        return taxonomy.category(id) ?: taxonomy.unknown()
    }
}
