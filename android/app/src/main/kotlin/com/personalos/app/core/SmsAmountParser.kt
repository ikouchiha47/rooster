package com.personalos.app.core

/**
 * Parses a transaction amount out of an SMS body (a `fields.amount` producer).
 *
 * Pure Kotlin, no Android dependency, so the corpus cases are JVM-testable.
 *
 * ## Conservative by construction
 *
 * A wrong amount makes a rule fire wrongly, which is worse than a missing one,
 * so this parser **prefers no value over a guessed one**. It emits a value only
 * when it can attribute it to a money movement:
 *
 * - the amount must carry a currency marker (`Rs`, `Rs.`, `INR`, `₹`). A bare
 *   number is never an amount — that is what keeps dates, account suffixes,
 *   OTP codes and phone numbers out;
 * - the amount must sit in a clause that names a transaction (`debited`,
 *   `credited`, `paid`, `sent`, `spent`, ...). A bill ("Rs 1,649.64 is due") or
 *   a promo ("Get Rs 500 cashback") is therefore not an amount;
 * - an amount introduced by a balance phrase (`Avl Bal`, `Available balance`)
 *   is skipped, so "debited by Rs 1,214.00 · Avl Bal Rs 12,345.67" yields the
 *   debit, not the balance;
 * - a multiplier suffix (`Rs 1.5 Lakh`) is rejected rather than read as `1.5`;
 * - more than one transaction amount in one message is ambiguous, so the parser
 *   emits nothing rather than picking one.
 *
 * Supported formats: `Rs.1,234.56`, `Rs 5000`, `INR 5,000`, `₹5000`,
 * `Rs.5000/-`, and Indian grouping (`INR 1,00,000`). Not supported: bare
 * numbers, non-INR currencies, worded amounts, and multiplier shorthand.
 */
object SmsAmountParser {
    /**
     * A rupee amount with a currency marker. The lookbehind stops `rs`/`inr`
     * matching inside a word; the trailing digits are required, so `Rs.` alone
     * and `Rsvp` never match.
     */
    private val CURRENCY_AMOUNT =
        Regex(
            """(?<![a-zA-Z])(?:rs\.?|inr|₹)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
            RegexOption.IGNORE_CASE,
        )

    /** An amount followed by one of these is shorthand, not a full value. */
    private val MULTIPLIER =
        Regex("""^(lakh|lakhs|lac|lacs|crore|crores|cr|million|mn|billion|bn|thousand|k)\b""", RegexOption.IGNORE_CASE)

    /** Language that marks a clause as money movement, on its own. */
    private val STRONG_TRANSACTION_SIGNAL =
        Regex(
            """\b(debited|credited|withdrawn|withdrawal|spent|transferred|transfer|paid|sent|received|deposited|deposit|refund|remitted)\b""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Language that usually means a transaction but also appears in bills
     * ("is due for payment"), so it needs a second look.
     */
    private val WEAK_TRANSACTION_SIGNAL =
        Regex(
            """\b(payment|purchase|txn|transaction|upi|neft|imps|rtgs|nach|salary)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Language that marks an amount as a bill, never a money movement. */
    private val BILL_SIGNAL =
        Regex(
            """\b(due|dues|outstanding|minimum\s+payment|statement|bill)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Language that marks an amount as a balance, never a transaction value. */
    private val BALANCE_SIGNAL =
        Regex(
            """(avl\.?\s*bal|avail(?:able)?\s+bal(?:ance)?|a/?c\s+bal(?:ance)?|closing\s+bal(?:ance)?|total\s+bal(?:ance)?|\bbalance\b|\bbal\b)""",
            RegexOption.IGNORE_CASE,
        )

    /** The transaction amount in [body], or null when none can be attributed safely. */
    fun parse(body: String): Double? {
        val matches = CURRENCY_AMOUNT.findAll(body).toList()
        if (matches.isEmpty()) return null

        val candidates =
            matches.mapIndexedNotNull { index, match ->
                val value =
                    match.groupValues[1].replace(",", "").toDoubleOrNull()
                        ?: return@mapIndexedNotNull null
                val left = body.substring(if (index == 0) 0 else matches[index - 1].range.last + 1, match.range.first)
                val right = body.substring(match.range.last + 1, if (index == matches.lastIndex) body.length else matches[index + 1].range.first)
                if (MULTIPLIER.containsMatchIn(right.trimStart())) return@mapIndexedNotNull null
                Candidate(value, left.takeLast(CONTEXT_CHARS), right.take(CONTEXT_CHARS))
            }
        if (candidates.isEmpty()) return null

        // One amount: only a transaction clause makes it a transaction value.
        if (candidates.size == 1) {
            val only = candidates.single()
            return only.takeIf { it.isTransaction() }?.value
        }

        // Several amounts: exactly one attributable to a money movement, no
        // balance among the rest. Two transaction amounts is ambiguous.
        val transaction = candidates.filterNot { it.isBalance() }.filter { it.isTransaction() }
        return transaction.singleOrNull()?.value
    }

    private data class Candidate(
        val value: Double,
        val left: String,
        val right: String,
    ) {
        fun isTransaction(): Boolean {
            val clause = "$left $right"
            if (STRONG_TRANSACTION_SIGNAL.containsMatchIn(clause)) return true
            return WEAK_TRANSACTION_SIGNAL.containsMatchIn(clause) && !BILL_SIGNAL.containsMatchIn(clause)
        }

        // A balance phrase introduces the amount it precedes, so only the
        // clause *before* an amount can mark it as a balance.
        fun isBalance(): Boolean = BALANCE_SIGNAL.containsMatchIn(left)
    }

    /** How much clause on either side of an amount is inspected. */
    private const val CONTEXT_CHARS = 48
}
