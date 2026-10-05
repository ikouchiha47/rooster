package com.personalos.app.core.tag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingTaggerTest {
    /**
     * One distinct direction per tag, in 5 dims. Distinct (not a shared component),
     * so the *centered* cosine of an aligned item is ~1 and of an unrelated one ~0 —
     * the arithmetic the tagger now relies on.
     */
    private val descriptions =
        mapOf(
            Tags.FINANCE to "money",
            Tags.INCIDENT to "disruption",
            Tags.TECH to "gadgets",
            Tags.TRAVEL to "trips",
            Tags.PROMO to "offers",
        )

    private fun oneHot(text: String): FloatArray? =
        when (text) {
            "money" -> floatArrayOf(1f, 0f, 0f, 0f, 0f)
            "disruption" -> floatArrayOf(0f, 1f, 0f, 0f, 0f)
            "gadgets" -> floatArrayOf(0f, 0f, 1f, 0f, 0f)
            "trips" -> floatArrayOf(0f, 0f, 0f, 1f, 0f)
            "offers" -> floatArrayOf(0f, 0f, 0f, 0f, 1f)
            else -> null
        }

    private fun embedderFor(item: FloatArray): suspend (List<String>) -> List<FloatArray?> = { texts -> texts.map { full -> oneHot(full.substringAfter("| query: ")) ?: item } }

    private fun input(
        text: String,
        sender: String? = null,
    ) = TagInput(text = text, source = Transport.SMS, sender = sender)

    private fun taggerFor(item: FloatArray) =
        EmbeddingTagger(
            embed = { null },
            descriptions = { descriptions },
            embedMany = embedderFor(item),
        )

    @Test
    fun `it tags by description similarity, with a confidence`() =
        runBlocking {
            // Aligned with finance and incident at once -> both survive (centered ≈ 0.57).
            val tagger = taggerFor(floatArrayOf(1f, 1f, 0f, 0f, 0f))

            val result = tagger.tag(input("bank strike"))

            assertTrue("finance clears the subject bar", Tags.FINANCE in result.tags)
            assertTrue("incident clears the nature bar", Tags.INCIDENT in result.tags)
            assertEquals(0.567f, result.confidence, 0.02f)
            assertEquals("ml-v${TagVersions.EMBEDDING}", result.taggerId)
        }

    @Test
    fun `an advert loses its subject but keeps its nature`() =
        runBlocking {
            val tagger = taggerFor(floatArrayOf(0f, 1f, 0f, 0f, 0f))

            val result = tagger.tag(input("Monsoon Sale 25% off", sender = "VM-SHOP-P"))

            assertFalse("marketing copy is not evidence of finance", Tags.FINANCE in result.tags)
            assertTrue("the nature still fires", Tags.INCIDENT in result.tags)
        }

    @Test
    fun `declared tags pass through, and a marker is never inferred`() =
        runBlocking {
            // On the centroid: centered to zero, so nothing is inferred.
            val tagger = taggerFor(floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f))

            val result = tagger.tag(input("x").copy(declaredTags = setOf(Tags.NEWS)))

            assertEquals(setOf(Tags.NEWS), result.tags)
        }

    // ------------------------------------------------- anisotropy (the real bug)

    private val anisotropicDescriptions =
        mapOf(Tags.FINANCE to "money", Tags.INCIDENT to "disruption", Tags.TECH to "gadgets")

    /** A large shared component plus one distinctive axis per tag. */
    private fun anisotropic(item: FloatArray): suspend (List<String>) -> List<FloatArray?> =
        { texts ->
            texts.map { full ->
                when (full.substringAfter("| query: ")) {
                    "money" -> floatArrayOf(3f, 1f, 1f)
                    "disruption" -> floatArrayOf(1f, 3f, 1f)
                    "gadgets" -> floatArrayOf(1f, 1f, 3f)
                    else -> item
                }
            }
        }

    @Test
    fun `a shared component does not tag an unrelated item`() =
        runBlocking {
            // Raw cosine of the neutral item against every tag is ~0.87 (near-identical),
            // so the old absolute thresholds fired all of them. Centering cancels the
            // shared part and the item scores ~0 against everything -> no tag.
            val tagger =
                EmbeddingTagger(
                    embed = { null },
                    descriptions = { anisotropicDescriptions },
                    embedMany = anisotropic(item = floatArrayOf(1f, 1f, 1f)),
                )

            val result = tagger.tag(input("a neutral sentence with no subject"))

            assertTrue("the shared component must not fire every tag", result.tags.isEmpty())
        }

    @Test
    fun `an aligned item still fires its tag after centering`() =
        runBlocking {
            val tagger =
                EmbeddingTagger(
                    embed = { null },
                    descriptions = { anisotropicDescriptions },
                    embedMany = anisotropic(item = floatArrayOf(3f, 1f, 1f)),
                )

            val result = tagger.tag(input("markets rally"))

            assertTrue("finance survives centering", Tags.FINANCE in result.tags)
        }
}
