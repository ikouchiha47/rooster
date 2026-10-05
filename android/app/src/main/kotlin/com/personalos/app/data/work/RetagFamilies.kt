package com.personalos.app.data.work

/**
 * The source families the retag walks, one job each. One owner of the list, so the
 * scheduler and the retag worker cannot disagree about what a "family" is.
 *
 * Each family gets its own work name and cursor, so SMS is not stuck behind the
 * feed backlog and can be cancelled, retried and inspected on its own.
 */
object RetagFamilies {
    val ALL: List<String> = listOf("sms", "rss:", "gnews:", "userrss:", "weather:", "fx:")

    /** The unique work name for one family's retag. */
    fun uniqueName(family: String): String = "backfill:retag:$family"
}
