package com.personalos.app.core.net

import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal blocking HTTP GET. Always call from [kotlinx.coroutines.Dispatchers.IO].
 * No third-party client: providers only need a couple of JSON GETs.
 */
object Http {
    val json: Json = Json { ignoreUnknownKeys = true }

    /** The single fetch identity behind every poll, catalog or source. */
    const val USER_AGENT = "PersonalRadar/0.1 (personal use)"

    fun getText(
        url: String,
        timeoutMs: Int = 12_000,
        accept: String = "application/json",
    ): String {
        val (code, body) = getRaw(url, timeoutMs, accept)
        check(code in 200..299) { "HTTP $code for $url" }
        return body
    }

    /** A fetched response: the status code survives, for callers that report gates honestly. */
    data class RawResponse(
        val code: Int,
        val body: String,
    )

    /**
     * Raw status code plus body (the error stream past 299), for callers that
     * must report *what happened* instead of throwing: the feed verifier, and
     * the source poller's per-source health.
     */
    fun getRaw(
        url: String,
        timeoutMs: Int = 12_000,
        accept: String = "application/rss+xml, application/atom+xml, application/xml, text/xml",
    ): RawResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", accept)
            val code = connection.responseCode
            val body =
                runCatching {
                    (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                }.getOrDefault("")
            RawResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }
}
