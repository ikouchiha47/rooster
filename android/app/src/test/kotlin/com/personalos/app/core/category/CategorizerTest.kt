package com.personalos.app.core.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CategorizerTest {
    private val taxonomy =
        CategoryTaxonomyJson.parse(File("src/main/assets/category/categorization.v1.json").readText())
    private val categorizer = Categorizer(taxonomy)

    private fun id(
        sender: String,
        body: String,
        isContact: Boolean = false,
    ): String = categorizer.categorize(sender, body, isContact).id

    // ------------------------------------------------------- the required corpus

    @Test
    fun `zomato login OTP from a service header is transactional`() {
        assertEquals(
            "transactional",
            id("CP-ZOMATO-S", "428208 is your login OTP for Zomato App. Do not share it with anyone."),
        )
    }

    @Test
    fun `uber login code from an unsaved number is transactional`() {
        assertEquals("transactional", id("+9157575128", "Your Uber code is 8623. Never share this code."))
    }

    @Test
    fun `a payment of rupees is transactional`() {
        assertEquals(
            "transactional",
            id("AD-Airtel", "a payment of Rs 1500.0 is updated against your Airtel Black Id 10101019632058."),
        )
    }

    @Test
    fun `a final reminder to repay a due is transactional`() {
        assertEquals(
            "transactional",
            id("CP-SmplP", "RE: final reminder to repay your Simpl due of Rs.275.99"),
        )
    }

    @Test
    fun `a paid order is transactional, not purchases`() {
        // Judgement call: money moved, so this outranks the order/delivery rule.
        assertEquals(
            "transactional",
            id(
                "TX-SWIGGY-S",
                "Your Swiggy Order #250685238143618 of Rs. 216.0 was successfully paid on 05/03/2026",
            ),
        )
    }

    @Test
    fun `an order out for delivery is purchases`() {
        assertEquals(
            "purchases",
            id(
                "BX-SHPRKT-S",
                "Arriving Today: Your Light Street order 1587 is out for delivery. Track @ https://shprkt.in/x",
            ),
        )
    }

    @Test
    fun `a delivered order is purchases`() {
        assertEquals("purchases", id("JX-GOSWFT-S", "Delivered: Your order #165080 from Bluorng is delivered."))
    }

    @Test
    fun `a redeem-and-percent-off message is promotions`() {
        assertEquals(
            "promotions",
            id(
                "VK-vONEMG",
                "Hi, Rs.100 added to your account. Redeem it using code GIFT100 & get upto 25% off on medicines",
            ),
        )
    }

    @Test
    fun `a promo code with flat off is promotions`() {
        assertEquals(
            "promotions",
            id("TM-MYCLIQ", "Have a promo code? Get FLAT Rs 1,400 OFF on your next order"),
        )
    }

    @Test
    fun `a TRAI message from a -G header is government`() {
        assertEquals(
            "government",
            id("JK-TRAIND-G", "TRAI never sends any message or makes any call, for verification."),
        )
    }

    @Test
    fun `a govt body without a -G suffix is government`() {
        assertEquals(
            "government",
            id(
                "JK-REGINF",
                "কেন্দ্রীয় সরকার/ডাকঘর/রেলওয়ে ইত্যাদির কর্মকর্তারা ঘুষ চাইলে, অভিযোগ দায়ের করার জন্য CBI, ACB, Kolkata-র সাথে যোগাযোগ করুন।",
            ),
        )
    }

    @Test
    fun `a KYC-update link is spam`() {
        assertEquals(
            "spam",
            id("+919876543210", "KYC is pending. Click http://bit.ly/xyz to update"),
        )
    }

    @Test
    fun `a will-be-stopped threat is spam`() {
        assertEquals(
            "spam",
            id(
                "AD-Airtel-S",
                "REMINDER! Due to non-payment, your Airtel Black services will be stopped shortly. Clear the bill.",
            ),
        )
    }

    @Test
    fun `a missed-call notice from an unsaved number is transactional`() {
        // Missed-call *detection* can feed Actions later; as a message it is a service notice.
        assertEquals(
            "transactional",
            id(
                "+918048160444",
                "Dear Customer, You have 2 missed calls from +918048160444 The last missed call was at 10:42 PM",
            ),
        )
    }

    // ---------------------------------------------------------------- precedence

    @Test
    fun `a saved contact wins even over promotional text`() {
        assertEquals("personal", id("+919876543210", "Get 50% off on your next order", isContact = true))
    }

    @Test
    fun `a -G header is government regardless of body`() {
        assertEquals("government", id("AX-CPGRMS-G", ""))
    }

    @Test
    fun `an unsaved number sending plain text is personal`() {
        assertEquals("personal", id("+919540488830", "Bike pe hu. Ghar ja k call karta."))
    }

    @Test
    fun `an unsaved number with no human text is unknown`() {
        assertEquals("unknown", id("+919876543210", "123456"))
    }

    @Test
    fun `an empty body is unknown`() {
        assertEquals("unknown", id("AD-Airtel", ""))
    }

    @Test
    fun `a -P header is promotions even without promo words`() {
        assertEquals("promotions", id("JX-BATAIn-P", "Celebrate Durga Puja in style at BATA!"))
    }

    // ------------------------------------------------------------------- helpers

    @Test
    fun `sender kind distinguishes contact, header, short code and number`() {
        assertEquals("contact", categorizer.senderKind("9876543210", isContact = true))
        assertEquals("header", categorizer.senderKind("BV-BSNLWB-S", isContact = false))
        assertEquals("short_code", categorizer.senderKind("57575701", isContact = false))
        assertEquals("number", categorizer.senderKind("+919876543210", isContact = false))
        assertEquals("number", categorizer.senderKind("9876543210", isContact = false))
        assertEquals("unknown", categorizer.senderKind("", isContact = false))
    }

    @Test
    fun `dlt category maps only the decisive suffixes`() {
        assertEquals("promotions", categorizer.dltCategory("JX-BATAIn-P")?.id)
        assertEquals("government", categorizer.dltCategory("JK-TRAIND-G")?.id)
        assertNull("service -S is not decisive", categorizer.dltCategory("AK-AIRDUE-S"))
        assertNull(categorizer.dltCategory("AD-Airtel"))
    }

    // --------------------------------------------------------------------- seams

    @Test
    fun `the fallback seam is consulted only after every rule abstains`() {
        val c = Categorizer(taxonomy, fallback = Fallback { _, _, _ -> "personal" })
        assertEquals("personal", c.categorize("AD-Airtel", "nothing matches here", false).id)
    }

    @Test
    fun `an abstaining fallback yields unknown`() {
        val c = Categorizer(taxonomy, fallback = Fallback { _, _, _ -> null })
        assertEquals("unknown", c.categorize("AD-Airtel", "nothing matches here", false).id)
    }

    @Test
    fun `a new matcher type is a new implementation, not a branch`() {
        val json =
            """
            {
              "id": "t", "version": 1, "unknownId": "unknown",
              "categories": [
                {"id": "promotions", "label": "Promotions"},
                {"id": "unknown", "label": "Unknown"}
              ],
              "rules": [
                {"id": "always", "categoryId": "promotions", "priority": 10, "matcherType": "always"}
              ]
            }
            """.trimIndent()
        val always =
            object : RuleMatcher {
                override val matcherType = "always"

                override fun matches(
                    rule: CategoryRule,
                    message: Message,
                    provenance: Provenance,
                ): Boolean = true
            }

        val c = Categorizer(CategoryTaxonomyJson.parse(json), matchers = BundledMatchers.all + always)

        assertEquals("promotions", c.categorize("AD-Airtel", "anything", false).id)
    }

    @Test
    fun `the regex matcher is available as open data`() {
        val rule = CategoryRule(id = "r", categoryId = "transactional", priority = 1, matcherType = "body_regex", pattern = """\bdue\b""")
        val message = Message("AD-Airtel", "your bill is due now", isContact = false)

        assertTrue(BodyRegexMatcher.matches(rule, message, Provenance("header", null)))
    }

    @Test
    fun `the seed declares the seven categories and the unknown anchor`() {
        assertEquals(
            setOf("personal", "transactional", "promotions", "purchases", "government", "spam", "unknown"),
            taxonomy.categories.map { it.id }.toSet(),
        )
        assertTrue(taxonomy.unknownId.isNotBlank())
        assertTrue("rules are data", taxonomy.rules.isNotEmpty())
    }
}
