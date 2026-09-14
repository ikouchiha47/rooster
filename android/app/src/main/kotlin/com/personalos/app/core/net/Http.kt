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

    fun getText(
        url: String,
        timeoutMs: Int = 12_000,
        accept: String = "application/json",
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "PersonalRadar/0.1 (personal use)")
            connection.setRequestProperty("Accept", accept)
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code for $url" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
