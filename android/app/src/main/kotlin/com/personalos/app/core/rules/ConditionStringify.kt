package com.personalos.app.core.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * ADR 0005 T10: serializes a [Condition] back to the frozen on-disk JSON that
 * [ConditionJson] parses. Pure — the draft compiler saves through here so a
 * catalog-built draft is never a parser rejection. Round-trips every leaf the
 * compiler emits.
 */
object ConditionStringify {
    fun stringify(condition: Condition): String = node(condition).toString()

    private fun node(condition: Condition): JsonObject =
        when (condition) {
            is Condition.All ->
                buildJsonObject {
                    put("all", JsonArray(condition.conditions.map { node(it) }))
                }
            is Condition.Any ->
                buildJsonObject {
                    put("any", JsonArray(condition.conditions.map { node(it) }))
                }
            is Condition.Subject -> buildJsonObject { put("subject", JsonPrimitive(condition.tag)) }
            is Condition.Nature -> buildJsonObject { put("nature", JsonPrimitive(condition.tag)) }
            is Condition.Marker -> buildJsonObject { put("marker", JsonPrimitive(true)) }
            is Condition.Mention ->
                buildJsonObject {
                    put(
                        "mention",
                        buildJsonObject {
                            put("kind", JsonPrimitive(condition.kind))
                            put("value", JsonPrimitive(condition.value))
                        },
                    )
                }
            is Condition.Source -> buildJsonObject { put("source", JsonPrimitive(condition.sourceId)) }
            is Condition.Field ->
                buildJsonObject {
                    put(
                        "field",
                        buildJsonObject {
                            put("name", JsonPrimitive(condition.name))
                            put("op", JsonPrimitive(condition.op.serialName))
                            put("value", condition.value.toJson())
                        },
                    )
                }
            is Condition.Text ->
                buildJsonObject {
                    put(
                        "text",
                        buildJsonObject {
                            put("pattern", JsonPrimitive(condition.matcher.pattern))
                            put("target", JsonPrimitive(condition.matcher.target.serialName))
                        },
                    )
                }
            is Condition.Crossing ->
                buildJsonObject {
                    put(
                        "crossing",
                        buildJsonObject {
                            put("field", JsonPrimitive(condition.field))
                            put("direction", JsonPrimitive(condition.direction.serialName))
                            put("value", JsonPrimitive(condition.threshold))
                            put("window", JsonPrimitive(condition.window.observations))
                        },
                    )
                }
            is Condition.Delta ->
                buildJsonObject {
                    put(
                        "delta",
                        buildJsonObject {
                            put("field", JsonPrimitive(condition.field))
                            put("by", JsonPrimitive(condition.by))
                            put("window", JsonPrimitive(condition.window.observations))
                        },
                    )
                }
            is Condition.Min ->
                buildJsonObject {
                    put(
                        "min",
                        buildJsonObject {
                            put("field", JsonPrimitive(condition.field))
                            put("op", JsonPrimitive(condition.op.serialName))
                            put("value", JsonPrimitive(condition.value))
                            put("window", JsonPrimitive(condition.window.observations))
                        },
                    )
                }
            is Condition.Max ->
                buildJsonObject {
                    put(
                        "max",
                        buildJsonObject {
                            put("field", JsonPrimitive(condition.field))
                            put("op", JsonPrimitive(condition.op.serialName))
                            put("value", JsonPrimitive(condition.value))
                            put("window", JsonPrimitive(condition.window.observations))
                        },
                    )
                }
        }

    private fun FieldValue.toJson(): JsonPrimitive =
        when (this) {
            is FieldValue.Num -> JsonPrimitive(value)
            is FieldValue.Str -> JsonPrimitive(value)
            is FieldValue.Flag -> JsonPrimitive(value)
        }
}
