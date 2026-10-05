package com.personalos.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SenderBucketTest {
    @Test
    fun `a saved contact is social however few messages`() {
        assertEquals(SenderBucket.SOCIAL, senderBucket(msgCount = 0, savedContact = true))
        assertEquals(SenderBucket.SOCIAL, senderBucket(msgCount = 1, savedContact = true))
    }

    @Test
    fun `twenty messages makes an unsaved number social, nineteen is not`() {
        assertEquals(SenderBucket.SOCIAL, senderBucket(msgCount = 20, savedContact = false))
        assertEquals(SenderBucket.PEOPLE, senderBucket(msgCount = 19, savedContact = false))
    }

    @Test
    fun `a handful from an unsaved number is others, one more is people`() {
        assertEquals(SenderBucket.OTHERS, senderBucket(msgCount = 5, savedContact = false))
        assertEquals(SenderBucket.PEOPLE, senderBucket(msgCount = 6, savedContact = false))
    }

    @Test
    fun `zero messages from an unsaved number is others`() {
        assertEquals(SenderBucket.OTHERS, senderBucket(msgCount = 0, savedContact = false))
    }

    @Test
    fun `the thresholds are arguments, so a caller can tune them`() {
        assertEquals(SenderBucket.SOCIAL, senderBucket(3, savedContact = false, socialMin = 3))
        assertEquals(SenderBucket.OTHERS, senderBucket(3, savedContact = false, othersMax = 3))
    }
}
