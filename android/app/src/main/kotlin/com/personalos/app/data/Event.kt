package com.personalos.app.data

import kotlinx.serialization.Serializable

@Serializable
data class Event(
    val id: Long = 0,
    val source: String,
    val type: String,
    val timestamp: Long,
    val title: String,
    val content: String,
    val entities: List<String> = emptyList(),
    val location: String? = null,
    val url: String? = null,
) {
    val stableKey: String
        get() = "$source:$type:$timestamp:$title"
}
