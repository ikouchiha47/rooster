package com.personalos.app.core.rules

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Small typed readers shared by [ConditionJson] and [ActionJson].
 *
 * Everything throws [IllegalArgumentException] with a message naming the key and
 * the JSON path, so a writer can surface a real error and a typo fails loudly at
 * write time rather than silently never matching (P7).
 */
private val ruleJson = Json {}

/** Parses [jsonText] and requires a JSON object, or throws. */
internal fun parseObject(
    jsonText: String,
    what: String,
): JsonObject {
    val element =
        try {
            ruleJson.parseToJsonElement(jsonText)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("$what: malformed JSON: ${e.message}", e)
        }
    require(element is JsonObject) { "$what: must be a JSON object" }
    return element
}

/** Rejects any key outside [allowed]. Sorted, so the message is stable in tests. */
internal fun JsonObject.rejectUnknown(
    allowed: Set<String>,
    where: String,
) {
    val unknown = keys - allowed
    require(unknown.isEmpty()) { "$where: unknown keys rejected: ${unknown.sorted()}" }
}

internal fun JsonObject.requireString(
    key: String,
    where: String,
): String {
    val element = this[key] ?: throw IllegalArgumentException("$where: missing required key $key")
    require(element is JsonPrimitive && element.isString) { "$where: $key must be a string" }
    return element.content
}

internal fun JsonObject.requireNonBlankString(
    key: String,
    where: String,
): String {
    val value = requireString(key, where)
    require(value.isNotBlank()) { "$where: $key must not be blank" }
    return value
}

internal fun JsonObject.requireDouble(
    key: String,
    where: String,
): Double {
    val element = this[key] ?: throw IllegalArgumentException("$where: missing required key $key")
    require(element is JsonPrimitive && !element.isString && element.doubleOrNull != null) {
        "$where: $key must be a number"
    }
    return element.doubleOrNull!!
}

/** A required, strictly positive integer (a series window is at least one observation). */
internal fun JsonObject.requirePositiveInt(
    key: String,
    where: String,
): Int {
    val element = this[key] ?: throw IllegalArgumentException("$where: missing required key $key")
    require(element is JsonPrimitive && !element.isString && element.longOrNull != null) {
        "$where: $key must be an integer"
    }
    val value = element.longOrNull!!
    require(value in 1..Int.MAX_VALUE.toLong()) { "$where: $key must be greater than 0, was $value" }
    return value.toInt()
}

/** An optional integer, defaulting to [orElse]. */
internal fun JsonObject.longOr(
    key: String,
    orElse: Long,
    where: String,
): Long {
    val element = this[key] ?: return orElse
    require(element is JsonPrimitive && !element.isString && element.longOrNull != null) {
        "$where: $key must be an integer"
    }
    return element.longOrNull!!
}

/** A field value: string, number or boolean, keeping its type for a typed comparison. */
internal fun JsonElement.asFieldValue(where: String): FieldValue {
    require(this is JsonPrimitive) { "$where: value must be a string, number or boolean" }
    return when {
        isString -> FieldValue.Str(content)
        booleanOrNull != null -> FieldValue.Flag(booleanOrNull!!)
        doubleOrNull != null -> FieldValue.Num(doubleOrNull!!)
        else -> throw IllegalArgumentException("$where: value must be a string, number or boolean")
    }
}
