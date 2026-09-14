package com.personalos.app.core.tag

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicTaggerTest {
    private val tagger = HeuristicTagger()

    private fun tags(
        text: String,
        source: SourceKind = SourceKind.RSS,
        sender: String? = null,
        declaredTags: Set<String> = emptySet(),
    ): Set<String> =
        runBlocking {
            tagger
                .tag(TagInput(text = text, source = source, sender = sender, declaredTags = declaredTags))
                .tags
        }

    @Test
    fun `a news feed declares news`() {
        assertTrue(tags("Metro clears safety trial", declaredTags = setOf(Tags.NEWS)).contains(Tags.NEWS))
    }

    @Test
    fun `a feed is never assumed to be news`() {
        // The regression this guards: a status feed's rows used to arrive tagged
        // `news`, because the tagger inferred the tag from "this is RSS".
        val result = tags("GRU (Sao Paulo) on 2026-09-18", declaredTags = setOf(Tags.INCIDENT))
        assertTrue(result.contains(Tags.INCIDENT))
        assertFalse(result.contains(Tags.NEWS))
    }

    @Test
    fun `a source can declare several tags at once`() {
        // A markets desk: news that is also finance, declared by the source.
        val result = tags("Sensex slips on rate worries", declaredTags = setOf(Tags.NEWS, Tags.FINANCE))
        assertTrue(result.containsAll(setOf(Tags.NEWS, Tags.FINANCE)))
    }

    @Test
    fun `sms is not news by default`() {
        assertFalse(tags("Rs 1,214 debited from A/c", source = SourceKind.SMS).contains(Tags.NEWS))
    }

    @Test
    fun `sms from a phone number is personal`() {
        val result =
            tags(
                text = "Are you coming home for dinner?",
                source = SourceKind.SMS,
                sender = "+919876543210",
            )
        assertTrue(result.contains(Tags.PERSONAL))
    }

    @Test
    fun `sms from a dlt header is not personal`() {
        val result =
            tags(
                text = "Rs 1,214 debited from A/c XX4021",
                source = SourceKind.SMS,
                sender = "VM-HDFCBK-S",
            )
        assertFalse(result.contains(Tags.PERSONAL))
    }

    @Test
    fun `sms from a short code is not personal`() {
        val result = tags("Your OTP is 123456", source = SourceKind.SMS, sender = "57575")
        assertFalse(result.contains(Tags.PERSONAL))
    }

    @Test
    fun `rss items are never personal`() {
        assertFalse(tags("Dinner recipes for the weekend").contains(Tags.PERSONAL))
    }

    @Test
    fun `an item can carry several tags at once`() {
        val result = tags("Cyclone disrupts flights, festival cancelled in Chennai", declaredTags = setOf(Tags.NEWS))
        assertTrue(result.containsAll(setOf(Tags.NEWS, Tags.WEATHER, Tags.TRAVEL)))
    }

    @Test
    fun `weather lexicon matches flood and heavy rain`() {
        val result = tags("Heavy rain triggers flood warning across Assam")
        assertTrue(result.contains(Tags.WEATHER))
    }

    @Test
    fun `finance lexicon matches market wording`() {
        val result = tags("Sensex falls 400 points as RBI holds rates")
        assertTrue(result.contains(Tags.FINANCE))
    }

    @Test
    fun `tech lexicon matches`() {
        assertTrue(tags("New semiconductor plant announced").contains(Tags.TECH))
    }

    @Test
    fun `expense lexicon matches a debit message`() {
        val result = tags("Rs 1,214 debited from A/c XX4021", source = SourceKind.SMS)
        assertTrue(result.contains(Tags.EXPENSE))
    }

    @Test
    fun `dlt promotional suffix beats the wording`() {
        val result =
            tags(
                text = "Get 50 percent off on your next order",
                source = SourceKind.SMS,
                sender = "VM-ABCDEF-P",
            )
        assertTrue(result.contains(Tags.PROMO))
    }

    @Test
    fun `a non promotional header does not force the promo tag`() {
        val result =
            tags(
                text = "Your order has been shipped",
                source = SourceKind.SMS,
                sender = "VM-ABCDEF-S",
            )
        assertFalse(result.contains(Tags.PROMO))
    }

    @Test
    fun `word boundaries stop down matching download`() {
        assertFalse(tags("The download completed successfully").contains(Tags.INCIDENT))
    }

    @Test
    fun `outage wording is an incident`() {
        assertTrue(tags("Broadband outage in Sector 5, service restored").contains(Tags.INCIDENT))
    }

    @Test
    fun `papers lexicon matches`() {
        assertTrue(tags("Preprint posted to arXiv on portfolio risk").contains(Tags.PAPER))
    }

    @Test
    fun `confidence is lower when nothing matched`() {
        val bare = runBlocking { tagger.tag(TagInput("qwerty zxcvb", SourceKind.WEB)) }
        val matched = runBlocking { tagger.tag(TagInput("flood warning", SourceKind.WEB)) }
        assertTrue(bare.confidence < matched.confidence)
        assertTrue(bare.tags.isEmpty())
    }

    @Test
    fun `every emitted tag is from the declared taxonomy`() {
        val result = tags("Sensex falls as RBI holds rates; monsoon floods disrupt trains")
        assertTrue(Tags.ALL.containsAll(result))
    }

    @Test
    fun `id and version describe the implementation`() {
        assertEquals(TaggerKind.HEURISTIC, tagger.kind)
        assertEquals(6, tagger.version)
        assertEquals("heuristic-v6", tagger.id)
    }

    // --------------------------------------------------- disasters and festivals

    @Test
    fun `a disaster story is both weather and an incident`() {
        // The user's example: "Floods hit 49 lakh people in 15 Bihar districts".
        // `weather` is the subject, `incident` is the nature - it carries both.
        val result =
            tags(
                "Floods hit 49 lakh people in 15 Bihar districts as rivers keep swelling",
                declaredTags = setOf(Tags.NEWS),
            )
        assertTrue(result.containsAll(setOf(Tags.NEWS, Tags.WEATHER, Tags.INCIDENT)))
    }

    @Test
    fun `a service outage is still an incident too`() {
        assertTrue(tags("Broadband outage in Sector 5, service restored").contains(Tags.INCIDENT))
    }

    @Test
    fun `a named festival is tagged as one`() {
        val result =
            tags(
                "Call to keep Durga Pujas in West Bengal vegetarian kicks up a storm",
                declaredTags = setOf(Tags.NEWS),
            )
        assertTrue(result.contains(Tags.FESTIVAL))
    }

    @Test
    fun `festival coverage includes the major regional ones`() {
        for (
        headline in
        listOf(
            "Onam celebrations begin across Kerala",
            "Pongal is celebrated across Tamil Nadu",
            "Bihu dance troupes perform in Guwahati",
            "Diwali shoppers throng markets",
        )
        ) {
            assertTrue("expected festival: $headline", tags(headline, declaredTags = setOf(Tags.NEWS)).contains(Tags.FESTIVAL))
        }
    }

    @Test
    fun `festival words do not fire on ordinary words`() {
        // `\bholi\b` must not match "holiday", and "down" must not match "download".
        assertFalse(tags("Holiday bookings surge this month", declaredTags = setOf(Tags.NEWS)).contains(Tags.FESTIVAL))
    }

    @Test
    fun `a promotional sms cannot claim festival as a subject`() {
        val result =
            tags(
                text = "Diwali Dhamaka Sale! Flat 50 percent off on electronics",
                source = SourceKind.SMS,
                sender = "VM-SHOPPY-P",
            )
        assertFalse("advert must not claim the festival subject", result.contains(Tags.FESTIVAL))
        assertTrue(result.contains(Tags.PROMO))
    }

    // ------------------------------------------- advertising is not subject matter

    @Test
    fun `a promotional sms does not claim a subject from marketing copy`() {
        // "Monsoon Sale" is a seasonal retail campaign, not weather. This is the
        // exact regression that put a phone's promo inbox into the Weather tile.
        val result =
            tags(
                text = "GameLoot Monsoon Sale is here! Get Mega Discounts on Consoles, Games, PCs",
                source = SourceKind.SMS,
                sender = "VA-GAMELT-P",
            )
        assertTrue("still classified as a promo", result.contains(Tags.PROMO))
        assertFalse("marketing copy must not claim weather", result.contains(Tags.WEATHER))
    }

    @Test
    fun `a promotional sms still gets its natures`() {
        // Only *subject* inference is gated; natures keep firing.
        val result =
            tags(
                text = "Rs 1,214 debited from A/c XX4021",
                source = SourceKind.SMS,
                sender = "VM-HDFCBK-P",
            )
        assertTrue(result.contains(Tags.EXPENSE))
        assertTrue(result.contains(Tags.PROMO))
    }

    @Test
    fun `a non-promotional alert keeps its subject`() {
        // A government disaster alert is not advertising, so `-G` is unaffected.
        val result =
            tags(
                text = "Heavy rain warning: flooding expected in low lying areas",
                source = SourceKind.SMS,
                sender = "JZ-NDMAEW-G",
            )
        assertTrue(result.contains(Tags.WEATHER))
    }

    @Test
    fun `rss is never treated as advertising`() {
        val result = tags("Monsoon Sale at the mall", declaredTags = setOf(Tags.NEWS))
        assertTrue(result.contains(Tags.WEATHER))
    }
}
