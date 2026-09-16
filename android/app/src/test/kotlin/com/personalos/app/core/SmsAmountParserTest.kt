package com.personalos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The amount producer over the formats the Indian bank/UPI SMS corpus actually
 * carries, and — more importantly — the cases where it must stay silent.
 *
 * A missing amount is a correct answer; a wrong one makes a rule fire wrongly.
 * Every negative case here is a deliberate refusal, not an oversight.
 */
class SmsAmountParserTest {
    // ------------------------------------------------------------- supported

    @Test
    fun `parses the supported currency formats`() {
        assertEquals(1234.56, SmsAmountParser.parse("Rs.1,234.56 debited from your account")!!, 0.001)
        assertEquals(5000.0, SmsAmountParser.parse("Rs 5000 debited")!!, 0.001)
        assertEquals(5000.0, SmsAmountParser.parse("INR 5,000 credited")!!, 0.001)
        assertEquals(5000.0, SmsAmountParser.parse("₹5000 spent at a merchant")!!, 0.001)
        assertEquals(5000.0, SmsAmountParser.parse("Rs.5000/- debited")!!, 0.001)
    }

    @Test
    fun `parses indian grouping`() {
        assertEquals(100_000.0, SmsAmountParser.parse("INR 1,00,000 credited")!!, 0.001)
        assertEquals(1_21_214.0, SmsAmountParser.parse("Rs.1,21,214 debited")!!, 0.001)
    }

    @Test
    fun `parses the real bank and UPI shapes`() {
        assertEquals(
            1214.0,
            SmsAmountParser.parse("Your a/c XX1234 has been debited by Rs 1,214.00 on 13-09-26.")!!,
            0.001,
        )
        assertEquals(
            45.0,
            SmsAmountParser.parse("Rs 45.00 debited from a/c XX1234 at Starbucks.")!!,
            0.001,
        )
        assertEquals(
            75_000.0,
            SmsAmountParser.parse("Salary of INR 75,000 credited to your account ending 4021.")!!,
            0.001,
        )
        assertEquals(
            1214.0,
            SmsAmountParser.parse("Rs.1214 debited from a/c ..4021.")!!,
            0.001,
        )
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun `no currency marker means no amount`() {
        assertNull(SmsAmountParser.parse("123456 is your OTP. Do not share it."))
        assertNull(SmsAmountParser.parse("Your order 4021 was shipped on 13-09-26."))
        assertNull(SmsAmountParser.parse("Call me on 9876543210 after 5 pm."))
    }

    @Test
    fun `a bill or a promo is not a transaction amount`() {
        assertNull(SmsAmountParser.parse("Your Airtel Black bill of Rs 1649.64 is due for payment today."))
        assertNull(SmsAmountParser.parse("Dues of Rs.1214 remain on your card."))
        assertNull(SmsAmountParser.parse("Get Rs 500 cashback on your next order. Shop now."))
        assertNull(SmsAmountParser.parse("Happy Ganesh Chaturthi! Shop for Rs 7,999 and get FLAT 25% OFF."))
    }

    @Test
    fun `a multiplier suffix is rejected rather than under-read`() {
        assertNull(SmsAmountParser.parse("A fare of Rs 1.5 Lakh was quoted."))
        assertNull(SmsAmountParser.parse("Budget of INR 2 Crore approved."))
    }

    @Test
    fun `two transaction amounts are ambiguous and yield nothing`() {
        assertNull(SmsAmountParser.parse("Rs 5000 debited and Rs 2000 credited to your account."))
    }

    @Test
    fun `an amount introduced as a balance is not the transaction value`() {
        // Two amounts: the debit is read, the balance is skipped even though the
        // sentence also contains "debited".
        assertEquals(
            1214.0,
            SmsAmountParser.parse("Rs.1,214.00 debited from a/c XX1234. Avl Bal Rs 12,345.67.")!!,
            0.001,
        )
        // A balance on its own is never a transaction.
        assertNull(SmsAmountParser.parse("Available balance is Rs 12,345.67."))
    }

    @Test
    fun `a bare currency marker is not an amount`() {
        assertNull(SmsAmountParser.parse("Pay via Rs. or INR transfer."))
    }
}
