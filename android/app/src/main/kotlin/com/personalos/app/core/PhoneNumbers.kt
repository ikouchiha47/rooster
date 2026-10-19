package com.personalos.app.core

/**
 * The **one** canonical identity for a phone number.
 *
 * `+91 98765 43210`, `09876543210` and `9876543210` are the same person, so they
 * must be the same conversation. Inbound SMS arrives as `+919876543210` while what
 * we write stores the number as typed — two strings, two threads, one contact. This
 * makes both sides agree at the **write boundary**, so the identity is settled once
 * rather than guessed per screen.
 *
 * Not libphonenumber: there is no table of dialling codes beyond the ones we can
 * actually meet, and an unknown country degrades to "leave it alone" rather than
 * inventing a prefix. A short code or a business header has no country and is
 * returned unchanged.
 */
object PhoneNumbers {
    /** Below this there is no phone number — a short code or a DLT header. */
    const val MIN_DIGITS = 7

    /** National significant numbers we can recognise as "already national". */
    private const val NATIONAL_DIGITS = 10

    /** ISO-3166 alpha-2 → dialling code. Deliberately short and extensible. */
    private val DIALING: Map<String, String> =
        mapOf(
            "IN" to "91",
            "US" to "1",
            "CA" to "1",
            "GB" to "44",
            "AE" to "971",
            "SG" to "65",
            "AU" to "61",
            "DE" to "49",
            "FR" to "33",
            "JP" to "81",
            "CN" to "86",
            "BR" to "55",
            "ZA" to "27",
            "PK" to "92",
            "BD" to "880",
            "LK" to "94",
            "NP" to "977",
        )

    /** The dialling code for an ISO country, or null when we do not know one. */
    fun dialingCode(countryIso: String?): String? = DIALING[countryIso?.trim()?.uppercase()]

    /**
     * E.164 where the country is known and the number is plausible; the input
     * trimmed otherwise. Idempotent: canonicalising an already-canonical number is
     * a no-op, so it is safe to apply on every write.
     */
    fun canonical(
        raw: String?,
        countryIso: String?,
    ): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return trimmed
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < MIN_DIGITS) return trimmed
        if (trimmed.startsWith("+")) return "+$digits"
        val national = digits.removePrefix("0")
        val code = dialingCode(countryIso) ?: return national
        // Already carries the code, e.g. "919876543210" from a network that omits "+".
        if (national.length > NATIONAL_DIGITS && national.startsWith(code)) return "+$national"
        return "+$code$national"
    }
}
