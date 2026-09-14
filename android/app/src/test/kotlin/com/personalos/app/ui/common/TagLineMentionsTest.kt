package com.personalos.app.ui.common

import com.personalos.app.data.MentionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mentions in the meta line: the stored rows exist, but until now nothing
 * displayed them — a row naming three parties showed no chip at all.
 * These pin the UI-side decisions: what shows, in what order, and what the
 * one-line clamp folds into `+N`.
 */
class TagLineMentionsTest {
    private fun mention(
        surface: String,
        confidence: Float,
        kind: String = "party",
    ): MentionEntity =
        MentionEntity(
            itemId = "ulid",
            kind = kind,
            surface = surface,
            entityId = null,
            confidence = confidence,
            mentionedAt = 0L,
        )

    @Test
    fun `alternate-name place guesses never reach the line`() {
        val picked = selectMentions(listOf(mention("Somewhere", 0.7f, "place")))
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `canonical places and parties show`() {
        val picked = selectMentions(listOf(mention("Kolkata", 0.85f, "place"), mention("BJP", 0.9f)))
        assertEquals(listOf("BJP", "Kolkata"), picked)
    }

    @Test
    fun `parties lead places and ties read alphabetically`() {
        val picked =
            selectMentions(
                listOf(
                    mention("Kolkata", 0.85f, "place"),
                    mention("TMC", 0.9f),
                    mention("BJP", 0.9f),
                    mention("Howrah", 0.85f, "place"),
                ),
            )
        assertEquals(listOf("BJP", "TMC", "Howrah", "Kolkata"), picked)
    }

    @Test
    fun `blank and duplicated surfaces collapse`() {
        val picked =
            selectMentions(
                listOf(
                    mention("BJP", 0.9f),
                    mention("bjp", 0.9f),
                    mention("  ", 0.9f),
                ),
            )
        assertEquals(listOf("BJP"), picked)
    }

    @Test
    fun `a tag-less row of three parties shows two plus one`() {
        val (visible, hidden) = metaTokens(emptyList(), listOf("AAP", "BJP", "Congress"), false)
        assertEquals(
            listOf(MetaToken.Mention("AAP"), MetaToken.Mention("BJP")),
            visible,
        )
        assertEquals(1, hidden)
    }

    @Test
    fun `subjects lead mentions lead natures inside the two slots`() {
        val (visible, hidden) =
            metaTokens(
                listOf("promo", "finance"),
                listOf("BJP", "Kolkata"),
                false,
            )
        assertEquals(
            listOf(MetaToken.Tag("finance"), MetaToken.Mention("BJP")),
            visible,
        )
        // Kolkata and promo fold into the count, never a third slot.
        assertEquals(2, hidden)
    }

    @Test
    fun `a mention naming what a tag already says spends no slot`() {
        val (visible, hidden) = metaTokens(listOf("finance"), listOf("Finance", "BJP"), false)
        assertEquals(
            listOf(MetaToken.Tag("finance"), MetaToken.Mention("BJP")),
            visible,
        )
        assertEquals(0, hidden)
    }

    @Test
    fun `tags alone behave exactly as before`() {
        val (visible, hidden) = metaTokens(listOf("finance", "tech", "promo"), emptyList(), false)
        assertEquals(listOf(MetaToken.Tag("finance"), MetaToken.Tag("tech")), visible)
        assertEquals(1, hidden)
    }
}
