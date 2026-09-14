package com.personalos.app.core

/**
 * SMS intent classification. Pure Kotlin, no Android dependencies, so it is
 * unit-testable on the JVM.
 *
 * Taxonomy and rationale: docs/SMS-CLASSIFICATION.md
 */
enum class SmsClass {
    PERSONAL,
    OTP,
    TRANSACTION,
    BILL,
    TRAVEL_LOGISTICS,
    UPDATES,
    PROMOTIONAL,
    SPAM_FRAUD,
}

enum class SenderType { ALPHANUMERIC, SHORTCODE, MOBILE, INTERNATIONAL, UNKNOWN }

/** TRAI/DLT sender suffix: -P promotional, -S service, -T transactional, -G government. */
enum class DltCategory { PROMOTIONAL, SERVICE, TRANSACTIONAL, GOVERNMENT, NONE }

data class SmsClassification(
    val klass: SmsClass,
    val confidence: Double,
    val senderType: SenderType,
    val dltCategory: DltCategory,
    val headerId: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val code: String? = null,
    val reasons: List<String> = emptyList(),
)

object SmsClassifier {
    private val dltRe = Regex("""^([A-Z]{2})-([A-Za-z0-9]{3,})-([PSTG])$""")
    private val mobileRe = Regex("""^(?:\+91)?[6-9]\d{9}$""")
    private val internationalRe = Regex("""^\+\d{7,15}$""")
    private val shortCodeRe = Regex("""^\d{4,6}$""")
    private val amountRe = Regex("""(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val otpCodeRe = Regex("""\b(\d{4,8})\b""")
    private val urlRe = Regex("""https?://|www\.|bit\.ly|t\.co|tinyurl|rb\.gy|shorturl|cutt\.ly""", RegexOption.IGNORE_CASE)

    // Masking may be x/xx/XX/*/./bullet, e.g. "a/c ..4021", "a/c XX1234", "card 4021".
    private val maskedAccountRe =
        Regex(
            """(?:a/?c|acct|card|account)[^0-9]{0,4}[xX*.\u2022]{0,6}\s*(\d{3,4})""",
            RegexOption.IGNORE_CASE,
        )

    private val otpWords =
        listOf(
            "otp",
            "one time password",
            "one-time password",
            "verification code",
            "do not share",
            "valid for",
            "login code",
            "auth code",
        )
    private val transactionWords =
        listOf(
            "debited",
            "credited",
            "withdrawn",
            "spent",
            "transferred",
            "txn",
            "transaction",
            "upi",
            "neft",
            "imps",
            "salary",
            "payment of",
            "paid to",
            "purchase of",
        )
    private val billWords =
        listOf(
            "due",
            "minimum payment",
            "bill",
            "recharge",
            "expires in",
            "expiring",
            "outstanding",
            "statement",
            "prepaid plan",
            "renewal",
            "auto-debit",
        )
    private val travelWords =
        listOf(
            "pnr",
            "train",
            "flight",
            "boarding",
            "departure",
            "arrival",
            "coach",
            "seat",
            "irctc",
            "airline",
            "check-in",
            "baggage",
        )
    private val logisticsWords =
        listOf(
            "out for delivery",
            "delivered",
            "courier",
            "shipment",
            "awb",
            "parcel",
            "package",
            "shipped",
            "tracking id",
            "consignment",
        )
    private val rideWords = listOf("driver", "ride", "trip", "cab", "pickup", "uber", "ola")
    private val promoWords =
        listOf(
            "% off",
            "off on",
            "flat ",
            "sale",
            "offer",
            "coupon",
            "use code",
            "promo",
            "discount",
            "deal",
            "loyalty",
            "reward points",
            "cashback",
            "limited period",
            "shop now",
            "buy now",
            "fiesta",
            "exclusive",
        )
    private val updateWords =
        listOf(
            "appointment",
            "confirmed",
            "booking",
            "scheduled",
            "resolved",
            "support ticket",
            "registration",
            "successfully",
            "reminder",
            "report",
            "statement is ready",
        )
    private val spamWords =
        listOf(
            "you have won",
            "you won",
            "winner",
            "lottery",
            "lucky draw",
            "gift card",
            "claim your",
            "congratulations",
            "prize",
        )
    private val phishingWords =
        listOf(
            "verify your account",
            "verify your identity",
            "verify your kyc",
            "kyc update",
            "account blocked",
            "account suspended",
            "account frozen",
            "account deactivated",
            "click here",
            "update your details immediately",
            "reactivate your account",
        )
    private val bankBrands =
        listOf(
            "sbi",
            "hdfc",
            "icici",
            "axis",
            "kotak",
            "pnb",
            "bank",
            "paytm",
            "phonepe",
            "gpay",
            "upi",
            "netbanking",
        )
    private val shorteners = listOf("bit.ly", "t.co", "tinyurl", "rb.gy", "cutt.ly", "shorturl")

    fun classify(
        sender: String,
        body: String,
    ): SmsClassification {
        val text = body.lowercase()
        val senderType = senderType(sender)
        val (dlt, header) = parseDlt(sender)
        val amount = extractAmount(body)
        val currency = if (amount != null) "INR" else null
        val reasons = mutableListOf<String>()

        // ---- stage 1: spam / fraud gate -------------------------------------
        var spamScore = 0
        if (spamWords.any { text.contains(it) }) {
            spamScore += 2
            reasons += "prize/lottery language"
        }
        if (phishingWords.any { text.contains(it) }) {
            spamScore += 2
            reasons += "phishing language"
        }
        if (urlRe.containsMatchIn(text) && shorteners.any { text.contains(it) }) {
            spamScore += 1
            reasons += "shortened URL"
        }
        if (senderType == SenderType.MOBILE && bankBrands.any { text.contains(it) }) {
            spamScore += 2
            reasons += "bank language from a personal number"
        }
        if (spamScore >= 2) {
            return SmsClassification(
                klass = SmsClass.SPAM_FRAUD,
                confidence = 0.8,
                senderType = senderType,
                dltCategory = dlt,
                headerId = header,
                amount = amount,
                currency = currency,
                reasons = reasons,
            )
        }

        // ---- stage 2: intent ------------------------------------------------
        // OTP
        if (otpWords.any { text.contains(it) }) {
            val code = otpCodeRe.find(body)?.groupValues?.get(1)
            return result(
                SmsClass.OTP,
                0.95,
                senderType,
                dlt,
                header,
                amount,
                currency,
                code,
                reasons + "otp language",
            )
        }

        // Promotional DLT sender (-P): the suffix is a near-perfect separator, so
        // it must outrank lexical matches like "recharge"/"bill"/"offer" that
        // routinely appear in marketing copy.
        if (dlt == DltCategory.PROMOTIONAL) {
            return result(
                SmsClass.PROMOTIONAL,
                0.9,
                senderType,
                dlt,
                header,
                amount,
                currency,
                null,
                reasons + "DLT promotional suffix",
            )
        }

        // Transaction (money movement) - strongest of the money classes
        if (transactionWords.any { text.contains(it) } && amount != null) {
            return result(
                SmsClass.TRANSACTION,
                0.92,
                senderType,
                dlt,
                header,
                amount,
                currency,
                null,
                reasons + "money-movement language with amount",
            )
        }

        // Bill / due
        if (billWords.any { text.contains(it) }) {
            return result(
                SmsClass.BILL,
                0.8,
                senderType,
                dlt,
                header,
                amount,
                currency,
                null,
                reasons + "bill/due language",
            )
        }

        // Travel and logistics
        if (travelWords.any { text.contains(it) }) {
            return result(
                SmsClass.TRAVEL_LOGISTICS,
                0.85,
                senderType,
                dlt,
                header,
                amount,
                currency,
                null,
                reasons + "travel language",
            )
        }
        if (logisticsWords.any { text.contains(it) } || rideWords.any { text.contains(it) }) {
            return result(
                SmsClass.TRAVEL_LOGISTICS,
                0.8,
                senderType,
                dlt,
                header,
                amount,
                currency,
                null,
                reasons + "logistics/ride language",
            )
        }

        // Promotional: the DLT suffix is a strong independent signal
        if (dlt == DltCategory.PROMOTIONAL || promoWords.any { text.contains(it) }) {
            val conf = if (dlt == DltCategory.PROMOTIONAL) 0.9 else 0.75
            return result(
                SmsClass.PROMOTIONAL,
                conf,
                senderType,
                dlt,
                header,
                amount,
                currency,
                null,
                reasons + if (dlt == DltCategory.PROMOTIONAL) "DLT promotional suffix" else "promotional language",
            )
        }

        // Informational business update
        if (updateWords.any { text.contains(it) }) {
            return result(
                SmsClass.UPDATES,
                0.7,
                senderType,
                dlt,
                header,
                amount,
                currency,
                null,
                reasons + "informational-update language",
            )
        }

        // Transactional sender with money but no amount matched
        if (dlt == DltCategory.TRANSACTIONAL || dlt == DltCategory.SERVICE) {
            if (transactionWords.any { text.contains(it) }) {
                return result(
                    SmsClass.TRANSACTION,
                    0.6,
                    senderType,
                    dlt,
                    header,
                    amount,
                    currency,
                    null,
                    reasons + "transactional sender with money language",
                )
            }
            return result(
                SmsClass.UPDATES,
                0.55,
                senderType,
                dlt,
                header,
                amount,
                currency,
                null,
                reasons + "transactional/service sender",
            )
        }

        // Fallback: a plain conversational message from a phone number
        val conf = if (senderType == SenderType.MOBILE) 0.6 else 0.4
        reasons += if (senderType == SenderType.MOBILE) "personal-number conversational" else "no strong signal"
        return result(SmsClass.PERSONAL, conf, senderType, dlt, header, amount, currency, null, reasons)
    }

    // ---------------------------------------------------------------- helpers

    private fun result(
        klass: SmsClass,
        confidence: Double,
        senderType: SenderType,
        dlt: DltCategory,
        header: String?,
        amount: Double?,
        currency: String?,
        code: String?,
        reasons: List<String>,
    ) = SmsClassification(klass, confidence, senderType, dlt, header, amount, currency, code, reasons)

    fun senderType(sender: String): SenderType {
        val s = sender.trim()
        return when {
            s.isEmpty() -> SenderType.UNKNOWN
            mobileRe.matches(s) -> SenderType.MOBILE
            internationalRe.matches(s) -> SenderType.INTERNATIONAL
            dltRe.matches(s) -> SenderType.ALPHANUMERIC
            s.any { it.isLetter() } -> SenderType.ALPHANUMERIC
            shortCodeRe.matches(s) -> SenderType.SHORTCODE
            else -> SenderType.UNKNOWN
        }
    }

    /** Parses the DLT suffix and header from `CC-HEADER-C`; falls back to the raw sender. */
    fun parseDlt(sender: String): Pair<DltCategory, String?> {
        val s = sender.trim()
        val match = dltRe.find(s)
        if (match != null) {
            val header = match.groupValues[2]
            val category =
                when (match.groupValues[3]) {
                    "P" -> DltCategory.PROMOTIONAL
                    "S" -> DltCategory.SERVICE
                    "T" -> DltCategory.TRANSACTIONAL
                    "G" -> DltCategory.GOVERNMENT
                    else -> DltCategory.NONE
                }
            return category to header
        }
        // Non-DLT commercial sender: keep the alphanumeric header for the brand map.
        val header = if (s.any { it.isLetter() }) s else null
        return DltCategory.NONE to header
    }

    fun extractAmount(body: String): Double? {
        val m = amountRe.find(body) ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    fun maskedAccountSuffix(body: String): String? = maskedAccountRe.find(body)?.groupValues?.get(1)
}
