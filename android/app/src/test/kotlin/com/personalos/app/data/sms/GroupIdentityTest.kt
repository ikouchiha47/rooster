package com.personalos.app.data.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupIdentityTest {
    private val a = "9836870428"
    private val b = "9434853991"
    private val c = "9000000000"

    private fun group(
        id: String,
        vararg people: String,
    ) = GroupRecord(id = id, participants = people.toList(), body = "", sentAt = 0L)

    @Test
    fun `same people always produce the same id`() {
        assertEquals(GroupIdentity.idFor(listOf(a, b)), GroupIdentity.idFor(listOf(a, b)))
    }

    @Test
    fun `order and repeats do not change the id`() {
        assertEquals(GroupIdentity.idFor(listOf(a, b)), GroupIdentity.idFor(listOf(b, a, b)))
    }

    @Test
    fun `a different member set is a different id`() {
        assertNotEquals(GroupIdentity.idFor(listOf(a, b)), GroupIdentity.idFor(listOf(a, b, c)))
    }

    @Test
    fun `the id is six hex characters`() {
        val id = GroupIdentity.idFor(listOf(a, b))
        assertEquals(6, id.length)
        assertEquals(id, id.filter { it in "0123456789abcdef" })
    }

    @Test
    fun `an exact member set rejoins its group`() {
        val existing = group("x", a, b)
        assertEquals(existing, GroupIdentity.bestMatch(listOf(a, b), listOf(existing)))
    }

    @Test
    fun `adding a member grows the existing group rather than making a second`() {
        val existing = group("x", a, b)
        assertEquals(existing, GroupIdentity.bestMatch(listOf(a, b, c), listOf(existing)))
    }

    @Test
    fun `the largest matching group wins when several are subsets`() {
        val small = group("small", a)
        val large = group("large", a, b)
        assertEquals(large, GroupIdentity.bestMatch(listOf(a, b, c), listOf(small, large)))
    }

    @Test
    fun `an unrelated set starts a new group`() {
        assertNull(GroupIdentity.bestMatch(listOf(c), listOf(group("x", a, b))))
    }

    @Test
    fun `a partly-overlapping but non-containing set is not a match`() {
        // {b, c} shares b with {a, b}, but not all of the group's members are
        // present, so it must not silently merge into it.
        assertNull(GroupIdentity.bestMatch(listOf(b, c), listOf(group("x", a, b))))
    }

    @Test
    fun `no people is no group`() {
        assertNull(GroupIdentity.bestMatch(emptyList(), listOf(group("x", a, b))))
    }

    @Test
    fun `blank members are dropped`() {
        assertEquals(GroupIdentity.idFor(listOf(a, b)), GroupIdentity.idFor(listOf(a, " ", b, "")))
    }
}
