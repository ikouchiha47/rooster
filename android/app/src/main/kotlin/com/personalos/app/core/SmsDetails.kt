package com.personalos.app.core

/**
 * Non-categorising facts pulled from an SMS body for **display only** — the
 * amount a message moved and the one-time code it carries. Pure Kotlin, no
 * Android dependency, so the cases are JVM-testable.
 *
 * This is deliberately separate from
 * [com.personalos.app.core.category.Categorizer]: classification is the one
 * owner of a message's category; these are presentation extras that never
 * decide a tab. The amount is the conservative [SmsAmountParser] value (a
 * missing amount is absent, never zero); the code is only read from a message
 * that actually reads as an OTP, so a stray number is never shown as one.
 */
object SmsDetails {
    private val OTP_LANGUAGE =
        Regex(
            """\b(otp|one[-\s]?time password|verification code|login code|auth code)\b""",
            RegexOption.IGNORE_CASE,
        )

    private val CODE = Regex("""\b(\d{4,8})\b""")

    /** The transaction amount, or null when none can be attributed safely. */
    fun amount(body: String): Double? = SmsAmountParser.parse(body)

    /** The OTP code a message carries, or null when it is not an OTP message. */
    fun code(body: String): String? = if (OTP_LANGUAGE.containsMatchIn(body)) CODE.find(body)?.groupValues?.get(1) else null
}
