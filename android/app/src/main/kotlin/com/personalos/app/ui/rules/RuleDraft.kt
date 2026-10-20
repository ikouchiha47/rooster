package com.personalos.app.ui.rules

import com.personalos.app.core.mention.MentionKind
import com.personalos.app.core.rules.ActionJson
import com.personalos.app.core.rules.Condition
import com.personalos.app.core.rules.ConditionJson
import com.personalos.app.core.rules.Delivery
import com.personalos.app.core.rules.FieldNames
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
    /**
     * The source the whole rule is scoped to, or `null` for **All** (unscoped).
     *
     * A scoped rule is the user's condition ANDed with the existing
     * [Condition.Source] leaf; All adds no clause, so it behaves exactly as an
     * unscoped rule always did. `null`, never a sentinel string, is "All": there
     * is one representation of an unset scope, and one representation of a set
     * one (the `source` leaf inside `condition_json`).
     */
    val scope: String? = null,
) {
    enum class Composition {
        ALL,
        ANY,
    }

    fun isValid(): Boolean = name.isNotBlank() && predicates.isNotEmpty() && predicates.all { it.isValid() }

    fun toConditionJson(): String {
        val array = JsonArray(predicates.map { it.toJson() })
        val body =
            when (composition) {
                Composition.ALL -> buildJsonObject { put("all", array) }
                Composition.ANY -> buildJsonObject { put("any", array) }
            }
        // All (`scope == null`) is unscoped: the condition is the user's alone,
        // so nothing changes for existing rules. A chosen source is ANDed around
        // it through the existing `source` leaf, emitted as the first child of a
        // top-level `all`. The user's composition is always the second child —
        // never a bare leaf — which is what lets [fromRule] tell this scope
        // clause apart from a `source` predicate the user added themselves.
        return if (scope == null) {
            body.toString()
        } else {
            buildJsonObject {
                put(
                    "all",
                    JsonArray(
                        listOf(
                            buildJsonObject { put("source", JsonPrimitive(scope)) },
                            body,
                        ),
                    ),
                )
            }.toString()
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
                scope = null,
            )

        fun fromRule(
            name: String,
            conditionJson: String,
            actionJson: String,
        ): RuleDraft {
            val condition = ConditionJson.parse(conditionJson)
            val action = ActionJson.parse(actionJson)
            val (scope, scoped) = extractScope(condition)
            val (composition, predicates) = extractPredicates(scoped)
            return RuleDraft(
                name = name,
                composition = composition,
                predicates = predicates,
                push = action.delivery == Delivery.PUSH,
                position = action.position,
                scope = scope,
            )
        }

        /**
         * Recovers a top-level scope clause, if the condition carries one.
         *
         * [toConditionJson] emits a scoped rule as `all:[{source}, <user body>]`,
         * where the user body is itself an `all`/`any` composition — never a bare
         * leaf. Requiring that exact shape means a user's own `source` predicate
         * (a bare leaf directly under their `all`) is **not** mistaken for a
         * scope, so nothing is promoted out of their predicate list and a save
         * with no edit changes nothing. A condition that does not carry the
         * paired shape is unscoped.
         */
        private fun extractScope(condition: Condition): Pair<String?, Condition> {
            if (condition !is Condition.All || condition.conditions.size != 2) return null to condition
            val head = condition.conditions[0]
            val tail = condition.conditions[1]
            return when {
                head is Condition.Source && (tail is Condition.All || tail is Condition.Any) -> head.sourceId to tail
                else -> null to condition
            }
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
                is Condition.Semantic -> PredicateDraft.Meaning(condition.text, condition.threshold, condition.vector.toList())
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
        // Kind is closed: only MentionKind constants exist today. A free-text kind
        // would silently never match because no extractor produces it.
        override fun isValid(): Boolean = kind.isNotBlank() && value.isNotBlank() && kind in setOf(MentionKind.PLACE, MentionKind.PARTY)

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
        // Field name is closed: only names a producer writes can match anything.
        // A name outside FieldNames.SUPPLIED silently never matches (ADR 0003 §13).
        override fun isValid(): Boolean = name.isNotBlank() && name in FieldNames.SUPPLIED

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

    data class Meaning(
        val text: String,
        val threshold: Float = 0.6f,
        /** Filled at save time by the embedder; empty while the user is typing. */
        val vector: List<Float> = emptyList(),
    ) : PredicateDraft {
        override fun isValid(): Boolean = text.isNotBlank()

        override fun toJson(): JsonObject =
            buildJsonObject {
                put(
                    "meaning",
                    buildJsonObject {
                        put("text", JsonPrimitive(text))
                        put("threshold", JsonPrimitive(threshold))
                        put("vector", JsonArray(vector.map { JsonPrimitive(it) }))
                    },
                )
            }

        override fun summary(): String = "meaning ~ \"$text\""
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
