package com.personalos.app.core.calendar

/**
 * Minimal iCalendar (RFC 5545) reader for holiday feeds — pure Kotlin, zero
 * `android.*`, so the same code runs in the app and in tests.
 *
 * Deliberately not a general ICS library: it reads the three fields a calendar
 * feed needs (`DTSTART`, `SUMMARY`, `UID`) and ignores everything else. Feeds
 * fold long lines and escape text, so both are handled here rather than at each
 * call site.
 *
 * A feed's national rows carry `country` in the UID and its state rows carry
 * the region code (`IN-WB`), which is how a row's scope is told apart without
 * trusting prose in the description.
 */
object IcsCalendar {
    fun parse(raw: String): List<CalendarEntry> {
        if (raw.isBlank()) return emptyList()
        return events(raw).mapNotNull { block -> event(block) }
    }

    /** `BEGIN:VEVENT` .. `END:VEVENT`, with continuation lines already unfolded. */
    private fun events(raw: String): List<String> =
        unfold(raw)
            .split("BEGIN:VEVENT")
            .drop(1)
            .map { it.substringBefore("END:VEVENT") }
            .filter { it.isNotBlank() }

    /**
     * RFC 5545 folding: a line starting with a space or tab continues the
     * previous one. Summaries routinely fold, so a parser that skips this reads
     * half a holiday name.
     */
    private fun unfold(raw: String): String = raw.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "")

    private fun event(block: String): CalendarEntry? {
        val date = property(block, "DTSTART")?.let(::datePart) ?: return null
        val uid = property(block, "UID") ?: return null
        val name = property(block, "SUMMARY")?.let(::unescape)?.trim().orEmpty()
        if (name.isEmpty()) return null
        return CalendarEntry(
            uid = uid,
            date = date,
            name = name,
            kind = kindOf(uid),
        )
    }

    /** The first property value, matched on the key and ignoring any `;PARAM=` suffix. */
    private fun property(
        block: String,
        key: String,
    ): String? =
        block
            .lineSequence()
            .firstOrNull { line -> line.startsWith("$key:") || line.startsWith("$key;") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** `DTSTART;VALUE=DATE:20260112` and `DTSTART:20260112T000000Z` both give `2026-01-12`. */
    private fun datePart(value: String): String? {
        val digits = value.take(8).filter(Char::isDigit)
        if (digits.length != 8) return null
        return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
    }

    /**
     * The feed's UID carries the scope: `2026-01-26IN408regcountry@host` is
     * national, `2026-01-12IN-WB102regregion@host` names a state. Reading it
     * from the UID (rather than from the prose description) also labels the
     * national rows that legitimately appear inside a state feed.
     */
    internal fun kindOf(uid: String): CalendarKind {
        val local = uid.substringBefore("@").replace(LEADING_DATE, "")
        return if ('-' in local) CalendarKind.REGIONAL else CalendarKind.NATIONAL
    }

    private val LEADING_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}")

    /** `\,` `\;` `\n` `\\` are the escapes a feed actually emits. */
    private fun unescape(value: String): String =
        value
            .replace("\\n", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
}
