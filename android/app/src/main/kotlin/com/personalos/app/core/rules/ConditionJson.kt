package com.personalos.app.core.rules

import com.personalos.app.core.tag.TagGroups
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.regex.PatternSyntaxException

/**
 * Parses `rules.condition_json` into a [Condition] (ADR 0003 §6, plan T2.1-T2.3).
 *
 * The shape is one single-key object per node:
 *
 * ```json
 * {"all": [
 *   {"subject": "games"},
 *   {"any": [
 *     {"nature": "incident"},
 *     {"text": {"pattern": "bandh", "target": "any"}}
 *   ]}
 * ]}
 * ```
 *
 * Composition uses `all` / `any`; leaves use one key each — `subject`, `nature`,
 * `marker`, `mention`, `source`, `field`, `text`, and the series predicates
 * `crossing`, `delta`, `min`, `max`.
 *
 * Unknown keys are rejected at every level, and a condition node must have
 * exactly one key, so a typo (`subjekt`, `targte`) fails loudly at write time
 * rather than silently never matching (P7).
 *
 * @throws IllegalArgumentException on malformed JSON, an unknown key, an invalid
 *   value, or an unusable regex.
 */
object ConditionJson {
    private val COMPOSITION = setOf("all", "any")

    private val LEAVES =
        setOf(
            "subject",
            "nature",
            "marker",
            "mention",
            "source",
            "field",
            "text",
            "crossing",
            "delta",
            "min",
            "max",
        )

    private val ALLOWED = COMPOSITION + LEAVES

    /** Parses and validates a condition document. */
    fun parse(json: String): Condition = node(parseObject(json, "condition"), "condition")

    private fun node(
        obj: JsonObject,
        where: String,
    ): Condition {
        require(obj.size == 1) {
            "$where: a condition node must have exactly one key, had ${obj.keys.sorted()}"
        }
        val key = obj.keys.first()
        val value = obj.getValue(key)
        return when (key) {
            "all" -> Condition.All(children(value, "all"))
            "any" -> Condition.Any(children(value, "any"))
            "subject" -> Condition.Subject(tagIn(value, TagGroups.SUBJECTS, "subject"))
            "nature" -> Condition.Nature(tagIn(value, TagGroups.NATURES, "nature"))
            "marker" -> marker(value)
            "mention" -> mention(value)
            "source" -> Condition.Source(nonBlank(value, "source"))
            "field" -> field(value)
            "text" -> text(value)
            "crossing" -> crossing(value)
            "delta" -> delta(value)
            "min" -> aggregate(value, "min")
            "max" -> aggregate(value, "max")
            else -> throw IllegalArgumentException("$where: unknown predicate '$key'; expected one of ${ALLOWED.sorted()}")
        }
    }

    private fun children(
        value: JsonElement,
        kind: String,
    ): List<Condition> {
        require(value is JsonArray) { "$kind: must be an array of conditions" }
        require(value.isNotEmpty()) { "$kind: needs at least one condition" }
        return value.mapIndexed { index, child ->
            require(child is JsonObject) { "$kind[$index]: must be a JSON object" }
            node(child, "$kind[$index]")
        }
    }

    private fun tagIn(
        value: JsonElement,
        allowed: Set<String>,
        kind: String,
    ): String {
        require(value is JsonPrimitive && value.isString) { "condition $kind: must be a string tag" }
        val tag = value.content
        require(tag in allowed) { "condition $kind: '$tag' is not a $kind tag; allowed: ${allowed.sorted()}" }
        return tag
    }

    private fun marker(value: JsonElement): Condition {
        require(value is JsonPrimitive && value.booleanOrNull != null) {
            "marker: must be true (there is no negation)"
        }
        require(value.booleanOrNull == true) { "marker: must be true (there is no negation)" }
        return Condition.Marker
    }

    private fun mention(value: JsonElement): Condition {
        val obj = asObject(value, "mention")
        obj.rejectUnknown(setOf("kind", "value"), "mention")
        return Condition.Mention(
            kind = obj.requireNonBlankString("kind", "mention"),
            value = obj.requireNonBlankString("value", "mention"),
        )
    }

    private fun field(value: JsonElement): Condition {
        val obj = asObject(value, "field")
        obj.rejectUnknown(setOf("name", "op", "value"), "field")
        val opName = obj.requireString("op", "field")
        val op =
            FieldOp.from(opName)
                ?: throw IllegalArgumentException("field: unknown op '$opName'; expected one of ${opNames()}")
        val fieldValue = obj.requireElement("value", "field").asFieldValue("field")
        requireComparable(op, fieldValue)
        return Condition.Field(
            name = obj.requireNonBlankString("name", "field"),
            op = op,
            value = fieldValue,
        )
    }

    private fun text(value: JsonElement): Condition {
        val obj = asObject(value, "text")
        obj.rejectUnknown(setOf("pattern", "target"), "text")
        val pattern = obj.requireNonBlankString("pattern", "text")
        require(pattern.length <= TextMatcher.MAX_PATTERN_LENGTH) {
            "text: pattern must be at most ${TextMatcher.MAX_PATTERN_LENGTH} characters, was ${pattern.length}"
        }
        val targetName = obj.requireString("target", "text")
        val target =
            TextTarget.from(targetName)
                ?: throw IllegalArgumentException(
                    "text: unknown target '$targetName'; expected one of ${TextTarget.entries.map { it.serialName }.sorted()}",
                )
        val matcher =
            try {
                TextMatcher(pattern, target)
            } catch (e: PatternSyntaxException) {
                throw IllegalArgumentException("text: invalid pattern '$pattern': ${e.description}", e)
            }
        return Condition.Text(matcher)
    }

    private fun crossing(value: JsonElement): Condition {
        val obj = asObject(value, "crossing")
        obj.rejectUnknown(setOf("field", "direction", "value", "window"), "crossing")
        val directionName = obj.requireString("direction", "crossing")
        val direction =
            CrossingDirection.from(directionName)
                ?: throw IllegalArgumentException(
                    "crossing: unknown direction '$directionName'; expected below or above",
                )
        return Condition.Crossing(
            field = obj.requireNonBlankString("field", "crossing"),
            direction = direction,
            threshold = obj.requireDouble("value", "crossing"),
            window = Window(obj.requirePositiveInt("window", "crossing")),
        )
    }

    private fun delta(value: JsonElement): Condition {
        val obj = asObject(value, "delta")
        obj.rejectUnknown(setOf("field", "by", "window"), "delta")
        return Condition.Delta(
            field = obj.requireNonBlankString("field", "delta"),
            by = obj.requireDouble("by", "delta"),
            window = Window(obj.requirePositiveInt("window", "delta")),
        )
    }

    private fun aggregate(
        value: JsonElement,
        kind: String,
    ): Condition {
        val obj = asObject(value, kind)
        obj.rejectUnknown(setOf("field", "op", "value", "window"), kind)
        val opName = obj.requireString("op", kind)
        val op =
            FieldOp.from(opName)
                ?: throw IllegalArgumentException("$kind: unknown op '$opName'; expected one of ${opNames()}")
        require(op.isNumeric) { "$kind: op must compare numbers (lt, lte, gt, gte), was ${op.serialName}" }
        val field = obj.requireNonBlankString("field", kind)
        val threshold = obj.requireDouble("value", kind)
        val window = Window(obj.requirePositiveInt("window", kind))
        return if (kind == "min") {
            Condition.Min(field, op, threshold, window)
        } else {
            Condition.Max(field, op, threshold, window)
        }
    }

    private fun requireComparable(
        op: FieldOp,
        value: FieldValue,
    ) {
        when (op) {
            FieldOp.LT, FieldOp.LTE, FieldOp.GT, FieldOp.GTE ->
                require(value is FieldValue.Num) {
                    "field: op ${op.serialName} needs a number, was ${value.kindName()}"
                }
            FieldOp.CONTAINS ->
                require(value is FieldValue.Str) {
                    "field: op contains needs a string, was ${value.kindName()}"
                }
            FieldOp.EQ, FieldOp.NE -> Unit
        }
    }

    private fun nonBlank(
        value: JsonElement,
        kind: String,
    ): String {
        require(value is JsonPrimitive && value.isString) { "condition $kind: must be a string" }
        require(value.content.isNotBlank()) { "condition $kind: must not be blank" }
        return value.content
    }

    private fun asObject(
        value: JsonElement,
        where: String,
    ): JsonObject {
        require(value is JsonObject) { "$where: must be an object" }
        return value
    }

    private fun JsonObject.requireElement(
        key: String,
        where: String,
    ): JsonElement = this[key] ?: throw IllegalArgumentException("$where: missing required key $key")

    private fun opNames(): List<String> = FieldOp.entries.map { it.serialName }.sorted()

    private fun FieldValue.kindName(): String =
        when (this) {
            is FieldValue.Num -> "a number"
            is FieldValue.Str -> "a string"
            is FieldValue.Flag -> "a boolean"
        }
}
