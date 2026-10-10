package com.personalos.app.data.work

/**
 * The source families the retag walks, with their **weights**.
 *
 * Weight is priority: SMS is the small family whose latency is felt, so it earns
 * 4× the credit of a feed family each round and is chosen far more often — but the
 * dispatcher's deficit accounting still gives every weight-1 family a turn every
 * round, so none starves.
 */
object RetagFamilies {
    val ALL: List<String> = listOf("sms", "rss:", "gnews:", "userrss:", "weather:", "fx:")

    private const val SMS_WEIGHT = 4
    private const val DEFAULT_WEIGHT = 1

    fun weight(family: String): Int = if (family == "sms") SMS_WEIGHT else DEFAULT_WEIGHT
}
