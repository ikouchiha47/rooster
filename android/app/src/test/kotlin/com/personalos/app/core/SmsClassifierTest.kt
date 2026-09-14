package com.personalos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsClassifierTest {
    // ------------------------------------------------------------- sender type

    @Test
    fun `alpha sender with DLT suffix is alphanumeric with category`() {
        val (category, header) = SmsClassifier.parseDlt("JX-BATAIn-P")
        assertEquals(DltCategory.PROMOTIONAL, category)
        assertEquals("BATAIn", header)
        assertEquals(SenderType.ALPHANUMERIC, SmsClassifier.senderType("JX-BATAIn-P"))
    }

    @Test
    fun `service suffix maps to SERVICE`() {
        assertEquals(DltCategory.SERVICE, SmsClassifier.parseDlt("AK-AIRDUE-S").first)
    }

    @Test
    fun `transactional and government suffixes map correctly`() {
        assertEquals(DltCategory.TRANSACTIONAL, SmsClassifier.parseDlt("VM-AMZNIN-T").first)
        assertEquals(DltCategory.GOVERNMENT, SmsClassifier.parseDlt("AX-CPGRMS-G").first)
    }

    @Test
    fun `ten digit indian mobile is a personal sender`() {
        assertEquals(SenderType.MOBILE, SmsClassifier.senderType("9876543210"))
        assertEquals(SenderType.MOBILE, SmsClassifier.senderType("+919876543210"))
    }

    @Test
    fun `short code is detected`() {
        assertEquals(SenderType.SHORTCODE, SmsClassifier.senderType("51234"))
    }

    // ---------------------------------------------------------------- amounts

    @Test
    fun `extracts rupee amount with indian grouping`() {
        assertEquals(1214.0, SmsClassifier.extractAmount("Dues of Rs.1214 remain")!!, 0.001)
        assertEquals(1649.64, SmsClassifier.extractAmount("Bill of Rs 1,649.64 is due")!!, 0.001)
        assertEquals(75000.0, SmsClassifier.extractAmount("INR 75,000 credited")!!, 0.001)
    }

    @Test
    fun `no amount returns null`() {
        assertNull(SmsClassifier.extractAmount("Hey, are we still on for dinner?"))
    }

    @Test
    fun `masked account suffix is extracted`() {
        assertEquals("4021", SmsClassifier.maskedAccountSuffix("debited from a/c ..4021"))
    }

    // ------------------------------------------------------------ intent rules

    @Test
    fun `otp with do-not-share is classified as OTP`() {
        val r = SmsClassifier.classify("VM-BANKIN-S", "482910 is your OTP. Do not share it with anyone.")
        assertEquals(SmsClass.OTP, r.klass)
        assertEquals("482910", r.code)
        assertTrue(r.confidence > 0.9)
    }

    @Test
    fun `debit alert is a transaction with amount`() {
        val r =
            SmsClassifier.classify(
                "VM-BANKIN-S",
                "Your a/c XX1234 has been debited by Rs 1,214.00 on 13-09-26.",
            )
        assertEquals(SmsClass.TRANSACTION, r.klass)
        assertEquals(1214.0, r.amount!!, 0.001)
        assertEquals("INR", r.currency)
    }

    @Test
    fun `salary credit is a transaction`() {
        val r =
            SmsClassifier.classify(
                "VM-CORPHR-S",
                "Salary of INR 75,000 credited to your account ending 4021.",
            )
        assertEquals(SmsClass.TRANSACTION, r.klass)
    }

    @Test
    fun `bill due is a bill reminder`() {
        val r =
            SmsClassifier.classify(
                "AK-AIRDUE-S",
                "Your Airtel Black bill of Rs 1649.64 for the bill period is due for payment today.",
            )
        assertEquals(SmsClass.BILL, r.klass)
    }

    @Test
    fun `rail pnr is travel logistics`() {
        val r =
            SmsClassifier.classify(
                "JX-IRCTCI-S",
                "PNR:6209444490,TRN:13114,DOJ:21-09-26,2S,BPC-KOAA,DP:17:25",
            )
        assertEquals(SmsClass.TRAVEL_LOGISTICS, r.klass)
    }

    @Test
    fun `package out for delivery is travel logistics`() {
        val r = SmsClassifier.classify("AD-AMZNIN-S", "Your Amazon package is out for delivery today.")
        assertEquals(SmsClass.TRAVEL_LOGISTICS, r.klass)
    }

    @Test
    fun `dlt promotional suffix alone implies promotional`() {
        val r = SmsClassifier.classify("VM-PHRMSY-P", "We have added Rs 75 new wallet credits, use them.")
        assertEquals(SmsClass.PROMOTIONAL, r.klass)
        assertTrue(r.reasons.contains("DLT promotional suffix"))
    }

    @Test
    fun `discount language is promotional`() {
        val r =
            SmsClassifier.classify(
                "VA-BENNTN-P",
                "Happy Ganesh Chaturthi! Shop for Rs 7,999 and get FLAT 25% OFF.",
            )
        assertEquals(SmsClass.PROMOTIONAL, r.klass)
    }

    @Test
    fun `appointment confirmation is an update`() {
        val r = SmsClassifier.classify("AD-CLINIC-S", "Confirmed: your appointment is tomorrow at 10 AM.")
        assertEquals(SmsClass.UPDATES, r.klass)
    }

    @Test
    fun `plain conversation from a person is personal`() {
        val r = SmsClassifier.classify("9876543210", "Hey, are we still on for dinner?")
        assertEquals(SmsClass.PERSONAL, r.klass)
        assertTrue(r.confidence >= 0.6)
    }

    // ------------------------------------------------------------- spam gate

    @Test
    fun `lottery with link is spam`() {
        val r =
            SmsClassifier.classify(
                "9876543210",
                "CONGRATS! You have won a Rs 1000 gift card. Click here: bit.ly/abc123",
            )
        assertEquals(SmsClass.SPAM_FRAUD, r.klass)
    }

    @Test
    fun `kyc phishing from a personal number is spam`() {
        val r =
            SmsClassifier.classify(
                "9123456780",
                "Your SBI account is suspended. Verify your KYC immediately at bit.ly/kyc",
            )
        assertEquals(SmsClass.SPAM_FRAUD, r.klass)
    }

    @Test
    fun `legitimate bank debit from a DLT sender is not spam`() {
        val r =
            SmsClassifier.classify(
                "VM-HDFCBK-S",
                "Rs 45.00 debited from a/c XX1234 at Starbucks.",
            )
        assertEquals(SmsClass.TRANSACTION, r.klass)
    }

    @Test
    fun `promotional sender with recharge wording stays promotional`() {
        val r =
            SmsClassifier.classify(
                "BN-661007-P",
                "Hi! your BSNL plan will expired soon. Recharge using Rs147,199,485 and offers.",
            )
        assertEquals(SmsClass.PROMOTIONAL, r.klass)
    }

    @Test
    fun `promotional sender with bill wording stays promotional`() {
        val r =
            SmsClassifier.classify(
                "VM-PHRMSY-P",
                "Your bill is ready. Pay using code SAVE20 and get 20% off.",
            )
        assertEquals(SmsClass.PROMOTIONAL, r.klass)
    }
}
