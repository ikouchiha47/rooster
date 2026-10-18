package com.personalos.app.core.category

/**
 * A provenance extractor: classifies *who* sent a message from the sender form
 * and the address-book flag. Ids are **open strings** (mirroring `KindAdapter`),
 * so a new sender form is a new implementation registered once — the evaluator
 * never learns a new branch.
 */
interface SenderKindRule {
    val kindId: String

    fun matches(
        sender: String,
        isContact: Boolean,
    ): Boolean
}

/** Relationship beats form: a saved number is a contact first. */
object ContactSenderKind : SenderKindRule {
    override val kindId = "contact"

    override fun matches(
        sender: String,
        isContact: Boolean,
    ): Boolean = isContact
}

/** A DLT/brand header, or any sender carrying letters (e.g. `BV-BSNLWB-S`). */
object HeaderSenderKind : SenderKindRule {
    override val kindId = "header"

    override fun matches(
        sender: String,
        isContact: Boolean,
    ): Boolean = sender.any { it.isLetter() }
}

/** A short numeric service id (e.g. `57575701`). */
object ShortCodeSenderKind : SenderKindRule {
    override val kindId = "short_code"

    override fun matches(
        sender: String,
        isContact: Boolean,
    ): Boolean = sender.digits().length in 4..9
}

/** A phone number: ten or more digits, with or without a country code. */
object NumberSenderKind : SenderKindRule {
    override val kindId = "number"

    override fun matches(
        sender: String,
        isContact: Boolean,
    ): Boolean = sender.digits().length >= 10
}

/** Open registry: order matters, the first matching rule wins. */
object BundledSenderKinds {
    val all: List<SenderKindRule> =
        listOf(
            ContactSenderKind,
            HeaderSenderKind,
            ShortCodeSenderKind,
            NumberSenderKind,
        )

    const val UNKNOWN = "unknown"
}

private val DLT_SUFFIX = Regex("""-([PSTG])$""")

/**
 * The TRAI/DLT category suffix of a header (`P` promotional, `S` service,
 * `T` transactional, `G` government), or null when the sender is not a DLT
 * header. The suffix *prior* is applied by the taxonomy's `header_suffix` rules,
 * not here.
 */
fun dltSuffix(sender: String): String? = DLT_SUFFIX.find(sender.trim().uppercase())?.groupValues?.get(1)

private fun String.digits(): String = filter { it.isDigit() }
