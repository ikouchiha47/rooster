package com.personalos.app.ui.rules

import com.personalos.app.core.rules.ActionJson
import com.personalos.app.core.rules.Condition
import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.Delivery
import com.personalos.app.core.rules.FieldOp
import com.personalos.app.core.rules.FieldValue
import com.personalos.app.core.rules.TextTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Editable draft of a rule, kept separate from the store so the builder is free
 * to mutate without touching the repository until save (CODE-DESIGN-GUIDELINES.md §1).
 *
 * Emits JSON that [ConditionJson] and [ActionJson] accept, so a draft is never
 * the source of a parser rejection.
 */
data class RuleDraft(
    val name: String,
    val composition: Composition,
    val predicates: List<PredicateDraft>,
    val push: Boolean,
    val position: Long,
) {
    enum class Composition {
        ALL,
        ANY,
    }

    fun isValid(): Boolean = name.isNotBlank() && predicates.isNotEmpty() && predicates.all { it.isValid() }

    fun toConditionJson(): String {
        val array = JsonArray(predicates.map { it.toJson() })
        return when (composition) {
            Composition.ALL -> buildJsonObject { put("all", array) }.toString()
            Composition.ANY -> buildJsonObject { put("any", array) }.toString()
        }
    }

    fun toActionJson(): String =
        buildJsonObject {
            put("delivery", JsonPrimitive(if (push) "push" else "none"))
            put("position", JsonPrimitive(position))
        }.toString()

    companion object {
        fun empty(): RuleDraft =
            RuleDraft(
                name = "",
                composition = Composition.ALL,
                predicates = emptyList(),
                push = true,
                position = 0L,
            )

        fun fromRule(
            name: String,
            conditionJson: String,
            actionJson: String,
        ): RuleDraft {
            val condition = ConditionJson.parse(conditionJson)
            val action = ActionJson.parse(actionJson)
            val (composition, predicates) = extractPredicates(condition)
            return RuleDraft(
                name = name,
                composition = composition,
                predicates = predicates,
                push = action.delivery == Delivery.PUSH,
                position = action.position,
            )
        }

        private fun extractPredicates(condition: Condition): Pair<Composition, List<PredicateDraft>> =
            when (condition) {
                is Condition.All -> Composition.ALL to condition.conditions.map { leafOf(it) }
                is Condition.Any -> Composition.ANY to condition.conditions.map { leafOf(it) }
                else -> Composition.ALL to listOf(leafOf(condition))
            }

        private fun leafOf(condition: Condition): PredicateDraft =
            when (condition) {
                is Condition.Subject -> PredicateDraft.Subject(condition.tag)
                is Condition.Nature -> PredicateDraft.Nature(condition.tag)
                is Condition.Marker -> PredicateDraft.Marker
                is Condition.Mention -> PredicateDraft.Mention(condition.kind, condition.value)
                is Condition.Source -> PredicateDraft.Source(condition.sourceId)
                is Condition.Field -> PredicateDraft.Field(condition.name, condition.op, condition.value)
                is Condition.Text -> PredicateDraft.Text(condition.matcher.pattern, condition.matcher.target)
                else -> PredicateDraft.Unsupported
            }
    }
}

sealed interface PredicateDraft {
    fun isValid(): Boolean

    fun toJson(): JsonObject

    fun summary(): String

    data class Subject(
        val tag: String,
    ) : PredicateDraft {
        override fun isValid(): Boolean = tag.isNotBlank()

        override fun toJson(): JsonObject = buildJsonObject { put("subject", JsonPrimitive(tag)) }

        override fun summary(): String = "subject is $tag"
    }

    data class Nature(
        val tag: String,
    ) : PredicateDraft {
        override fun isValid(): Boolean = tag.isNotBlank()

        override fun toJson(): JsonObject = buildJsonObject { put("nature", JsonPrimitive(tag)) }

        override fun summary(): String = "nature is $tag"
    }

    data object Marker : PredicateDraft {
        override fun isValid(): Boolean = true

        override fun toJson(): JsonObject = buildJsonObject { put("marker", JsonPrimitive(true)) }

        override fun summary(): String = "marker is news"
    }

    data class Mention(
        val kind: String,
        val value: String,
    ) : PredicateDraft {
        override fun isValid(): Boolean = kind.isNotBlank() && value.isNotBlank()

        override fun toJson(): JsonObject =
            buildJsonObject {
                put(
                    "mention",
                    buildJsonObject {
                        put("kind", JsonPrimitive(kind))
                        put("value", JsonPrimitive(value))
                    },
                )
            }

        override fun summary(): String = "$kind is $value"
    }

    data class Source(
        val sourceId: String,
    ) : PredicateDraft {
        override fun isValid(): Boolean = sourceId.isNotBlank()

        override fun toJson(): JsonObject = buildJsonObject { put("source", JsonPrimitive(sourceId)) }

        override fun summary(): String = "source is $sourceId"
    }

    data class Field(
        val name: String,
        val op: FieldOp,
        val value: FieldValue,
    ) : PredicateDraft {
        override fun isValid(): Boolean = name.isNotBlank()

        override fun toJson(): JsonObject =
            buildJsonObject {
                put(
                    "field",
                    buildJsonObject {
                        put("name", JsonPrimitive(name))
                        put("op", JsonPrimitive(op.serialName))
                        put("value", fieldValuePrimitive(value))
                    },
                )
            }

        override fun summary(): String = "$name ${opSymbol(op)} ${formatFieldValueForSummary(value)}"
    }

    data class Text(
        val pattern: String,
        val target: TextTarget,
    ) : PredicateDraft {
        override fun isValid(): Boolean = pattern.isNotBlank()

        override fun toJson(): JsonObject =
            buildJsonObject {
                put(
                    "text",
                    buildJsonObject {
                        put("pattern", JsonPrimitive(pattern))
                        put("target", JsonPrimitive(target.serialName))
                    },
                )
            }

        override fun summary(): String = "text(${target.serialName}) ~ \"$pattern\""
    }

    data object Unsupported : PredicateDraft {
        override fun isValid(): Boolean = false

        override fun toJson(): JsonObject = buildJsonObject {}

        override fun summary(): String = "unsupported predicate"
    }
}

private fun opSymbol(op: FieldOp): String =
    when (op) {
        FieldOp.EQ -> "="
        FieldOp.NE -> "!="
        FieldOp.LT -> "<"
        FieldOp.LTE -> "<="
        FieldOp.GT -> ">"
        FieldOp.GTE -> ">="
        FieldOp.CONTAINS -> "contains"
    }

private fun formatFieldValueForSummary(value: FieldValue): String =
    when (value) {
        is FieldValue.Num -> value.value.toString().removeSuffix(".0")
        is FieldValue.Str -> "\"${value.value}\""
        is FieldValue.Flag -> value.value.toString()
    }

private fun fieldValuePrimitive(value: FieldValue): JsonPrimitive =
    when (value) {
        is FieldValue.Num -> JsonPrimitive(value.value)
        is FieldValue.Str -> JsonPrimitive(value.value)
        is FieldValue.Flag -> JsonPrimitive(value.value)
    }
